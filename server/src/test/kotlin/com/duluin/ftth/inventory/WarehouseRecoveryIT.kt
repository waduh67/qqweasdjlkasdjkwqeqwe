package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.fulfillment.WarehouseNumericLifecycleFixture
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.service.WarehouseOutboxDispatcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.context.event.ContextClosedEvent
import org.springframework.test.annotation.DirtiesContext
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Full numeric fixture; only the lost inspection response and delivery ACK are withheld. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["server.address=127.0.0.1"])
@Import(ReceiptRealStorage::class, WarehouseResponseLossConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseRecoveryIT : WarehouseNumericLifecycleFixture() {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var loss: WarehouseResponseLossProbe
    private lateinit var case: NumericCase
    private lateinit var returned: NumericReturn
    private lateinit var prior: ConfigurableApplicationContext
    private lateinit var lease: WarehouseDeliveryLease
    private lateinit var audit: String
    private lateinit var usage: SavedCommand
    private val closed = CountDownLatch(1)
    private var loseInspection = true

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    fun a_committedInspectionSurvivesLostSocketResponseAndUnacknowledgedRealDelivery() {
        case = numericCase()
        usage = numericUse(case)
        val installed = numericInstall(case)
        val signature = numericHandover(case, installed)
        returned = numericReturn(case, usage)
        assertThat(loseInspection).isFalse()
        numericComplete(case, signature)
        saveCommand("/api/work-orders/${case.workOrder}/approve", case.stock.token, "{}", "recovery-approve")
        assertThat(numericTotals(case)).isEqualTo("917500|82500|0|9|1|1|10|1")
        audit = numericAudit(case)
        lease = deliverWithoutAcknowledgement()
        assertObservation()
        fixture(case.stock.token).transaction {
            assertThat(scalar("SELECT state FROM inventory_outbox_delivery WHERE id='${lease.eventId}'")).isEqualTo("LEASED")
        }
        prior = context
        prior.addApplicationListener { event -> if (event is ContextClosedEvent && event.applicationContext === prior) closed.countDown() }
    }

    @Test
    fun b_freshApplicationReplaysExactInspectionAndRedeliversWithOneOwnerEffect() {
        assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(prior.isActive).isFalse()
        assertThat(context).isNotSameAs(prior)
        assertThat(numericAudit(case)).isEqualTo(audit)
        val original = returned.inspection
        assertThat(saveCommand(original.path, original.token, original.body, original.key, original.status)).isEqualTo(original)
        val probe = fixture(case.stock.token)
        probe.transaction {
            // Expire scheduling metadata only. Physical rows and immutable event bodies are untouched.
            sql("UPDATE inventory_outbox_delivery SET lease_until=clock_timestamp()-interval '1 microsecond',revision=revision+1 WHERE id='${lease.eventId}'")
        }
        TenantContext.runAs(probe.tenant) {
            val store = context.getBean(WarehouseOutboxDeliveryStore::class.java)
            assertThat(store.delivered(lease)).isFalse()
            val dispatcher = context.getBean(WarehouseOutboxDispatcher::class.java)
            var drained = false
            repeat(100) { if (!drained) drained = !dispatcher.dispatchOne() }
            assertThat(drained).isTrue()
            assertThat(store.delivered(lease)).isFalse()
        }
        assertObservation()
        probe.transaction {
            assertThat(scalar("SELECT state||'|'||attempts FROM inventory_outbox_delivery WHERE id='${lease.eventId}'")).isEqualTo("DELIVERED|2")
            assertThat(scalar("SELECT count(*) FROM inventory_operation WHERE operation_key='numeric-return-inspect'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM fulfillment_approval_snapshot WHERE work_order_id='${case.workOrder}'")).isEqualTo("1")
            assertThat(scalar("SELECT state FROM fulfillment_checkpoint WHERE work_order_id='${case.workOrder}'")).isEqualTo("APPLIED")
        }
        assertThat(numericAudit(case)).isEqualTo(audit)
        assertThat(numericTotals(case)).isEqualTo("917500|82500|0|9|1|1|10|1")
        val foreign = tenant()
        assertThat(http(original.path, foreign, original.body, original.key).statusCode()).isEqualTo(404)
        val historicalUsage = "/api/v1/warehouse/my-material-usage/${mapper.readTree(usage.original).path("usageId").asString()}"
        assertThat(http(historicalUsage, usage.token, null, "read").statusCode()).isEqualTo(200)
        val accepted = case.receipt.path("lines").first().path("accepted").path("locationId").asString()
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${case.technician.second}/$accepted", case.stock.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(http(historicalUsage, usage.token, null, "read").statusCode()).isEqualTo(404)
        val closedJob = http(usage.path, usage.token, usage.body, usage.key)
        assertThat(closedJob.statusCode()).isEqualTo(409)
        assertThat(closedJob.body()).isNotEqualTo(usage.original)
        assertThat(numericAudit(case)).isEqualTo(audit)
        assertThat(numericTotals(case)).isEqualTo("917500|82500|0|9|1|1|10|1")
    }

    override fun saveCommand(path: String, token: String, body: String, key: String, status: Int): SavedCommand {
        if (loseInspection && path.endsWith("/inspect")) {
            loseInspection = false
            val gate = loss.arm(path)
            try {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 5000
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(("POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\n" +
                        "Idempotency-Key: $key\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                    assertThat(gate.committed.await(30, TimeUnit.SECONDS)).isTrue()
                    assertThat(gate.status).isEqualTo(status)
                    assertThat(socket.getInputStream().available()).isZero()
                    val saved = fixture(token).transaction {
                        assertThat(scalar("SELECT count(*) FROM inventory_operation WHERE operation_key='$key'")).isEqualTo("1")
                        SavedCommand(path, token, body, key, status, scalar("SELECT original_body FROM inventory_operation WHERE operation_key='$key'"))
                    }
                    socket.setSoLinger(true, 0)
                    return saved
                }
            } finally {
                gate.release.countDown()
                assertThat(gate.finished.await(10, TimeUnit.SECONDS)).isTrue()
                loss.clear(gate)
            }
        }
        val response = http(path, token, body, key)
        assertThat(response.statusCode()).withFailMessage("$path: ${response.body()}").isEqualTo(status)
        return SavedCommand(path, token, body, key, status, response.body())
    }

    private fun http(path: String, token: String, body: String?, key: String): HttpResponse<String> = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build().use { client ->
            client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token").header("Idempotency-Key", key).header("Content-Type", "application/json")
                .apply { if (body == null) GET() else POST(HttpRequest.BodyPublishers.ofString(body)) }.build(), HttpResponse.BodyHandlers.ofString())
        }

    private fun deliverWithoutAcknowledgement(): WarehouseDeliveryLease {
        val probe = fixture(case.stock.token)
        return TenantContext.runAs(probe.tenant) {
            val store = context.getBean(WarehouseOutboxDeliveryStore::class.java)
            val reader = context.getBean(WarehouseDeliveryReader::class.java)
            val destination = context.getBean(WarehouseOutboxDeliveryPort::class.java)
            repeat(100) {
                val claimed = requireNotNull(store.claim(UUID.randomUUID()))
                val message = try { reader.read(claimed) } catch (_: UnsupportedWarehouseDelivery) {
                    assertThat(store.failed(claimed, WarehouseDeliveryFailure.NO_HANDLER)).isTrue()
                    return@repeat
                }
                destination.deliver(message)
                if (message.event.documentId.toString() == returned.caseId && message.event.kind == WarehouseEventKind.RETURN_RECEIVED) {
                    return@runAs claimed
                }
                assertThat(store.delivered(claimed)).isTrue()
            }
            error("Actual inspection delivery not found")
        }
    }

    private fun assertObservation() = fixture(case.stock.token).transaction {
        assertThat(scalar("SELECT count(*) FROM inventory_inbox WHERE event_id='${lease.eventId}' AND consumer='fulfillment.warehouse-provenance.v1'")).isEqualTo("1")
        assertThat(scalar("SELECT count(*) FROM fulfillment_warehouse_observation WHERE id='${lease.eventId}' AND document_id='${returned.caseId}'")).isEqualTo("1")
    }
}
