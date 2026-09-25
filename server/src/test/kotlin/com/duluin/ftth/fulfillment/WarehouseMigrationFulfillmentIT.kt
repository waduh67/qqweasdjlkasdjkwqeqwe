package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.*
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReceiptRealStorage::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseMigrationFulfillmentIT : WarehousePolicyHttpFixture() {
    private class Legacy {
        val stock = WarehouseMigrationLegacyFixture()
        val workOrder = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val ids = List(3) { UUID.randomUUID() }
        val outbox = List(3) { UUID.randomUUID() }
        fun request(index: Int) = FulfillmentRequest(stock.tenant, "legacy.fulfillment", ids[index].toString(),
            ('a' + index).toString().repeat(64), FulfillmentSource.WORK_ORDER, workOrder, null, workOrder, "REPAIR", true,
            setOf(FulfillmentEffectType.INVENTORY, FulfillmentEffectType.WORK_ORDER))
        fun seed(connection: Connection) {
            stock.seed(connection)
            connection.createStatement().use { sql ->
                sql.execute("INSERT INTO app_user(id,tenant_id,email,name,password_hash) VALUES ('$actor','${stock.tenant}','$actor@example.test','Original actor','unused')")
                sql.execute("""INSERT INTO work_order(id,tenant_id,code,type,title,status,customer_id,created_by)
                    VALUES ('$workOrder','${stock.tenant}','LEGACY-WO','REPAIR','Legacy work','IN_PROGRESS','${stock.customer}','$actor')""")
                listOf("DISPATCHED", "APPLIED", "MANUAL_RESOLVED").forEachIndexed { index, state ->
                    val request = request(index)
                    sql.execute("""INSERT INTO fulfillment_checkpoint(id,tenant_id,namespace,operation_key,canonical_hash,source,target_id,state,
                        checkpoint_updated_at,work_order_id,work_order_kind,required_effects,outcome) VALUES ('${ids[index]}','${stock.tenant}',
                        '${request.namespace}','${request.operationKey}','${request.canonicalHash}','WORK_ORDER','$workOrder','$state',now(),
                        '$workOrder','REPAIR','INVENTORY,WORK_ORDER',${if (index == 2) "'Original manual resolution'" else "NULL"})""")
                    connection.prepareStatement("""INSERT INTO fulfillment_outbox(id,tenant_id,fulfillment_id,sequence,event_type,payload_hash,payload)
                        VALUES (?,?,?,1,'FULFILLMENT_APPLY',?,?)""").use { statement ->
                        statement.setObject(1, outbox[index]); statement.setObject(2, stock.tenant); statement.setObject(3, ids[index])
                        statement.setString(4, request.canonicalHash); statement.setString(5, request.encode()); statement.executeUpdate()
                    }
                    if (index == 1) sql.execute("""INSERT INTO fulfillment_effect_progress(id,tenant_id,fulfillment_id,effect_type,status,completed_at)
                        VALUES ('${UUID.randomUUID()}','${stock.tenant}','${ids[index]}','INVENTORY','COMPLETED',now())""")
                }
            }
        }
    }
    companion object {
        private val legacy = List(3) { Legacy() }
        private val database = WarehouseSchemaDatabase("172").also { db -> db.ownerFixture { connection -> legacy.forEach { it.seed(connection) } } }
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
        }
        @JvmStatic @AfterAll fun cleanup() { database.close() }
    }
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var storage: ObjectStorage
    @Autowired private lateinit var dataSource: DataSource
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private fun setup(index: Int): String {
        val old = legacy[index]
        val admin = tenant("migration-${old.stock.tenant}")
        val actor = mapper.readTree(request("GET", "/api/me", admin).contentAsString).path("id").asString()
        database.ownerFixture { connection -> connection.createStatement().use { sql ->
            sql.execute("SET app.tenant_id='${old.stock.tenant}'")
            sql.execute("UPDATE inventory_location SET name='Gudang lama',area_id='${area(admin)}',revision=revision+1 WHERE id='${old.stock.location}'")
            sql.execute("UPDATE customer SET area_id='${area(admin)}' WHERE id='${old.stock.customer}'")
            sql.execute("UPDATE work_order SET area_id='${area(admin)}' WHERE id='${old.workOrder}'")
        } }
        grant(admin, actor, listOf(old.stock.location.toString()))
        return admin
    }
    private fun send(admin: String, path: String, body: String) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        .header("Authorization", "Bearer $admin").header("Idempotency-Key", UUID.randomUUID().toString())
        .header("Content-Type", "application/json").timeout(Duration.ofSeconds(25)).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    private fun begin(admin: String): String {
        val report = request("GET", "/api/v1/warehouse/provenance", admin)
        assertThat(report.status).withFailMessage(report.contentAsString).isEqualTo(200)
        val body = mapper.readTree(report.contentAsString)
        assertThat(body.path("sourceCount").asInt()).isEqualTo(17)
        assertThat(body.path("pendingLegacyFulfillmentCount").asInt()).isEqualTo(1)
        assertThat(body.path("pendingLegacyOutboxCount").asInt()).isEqualTo(1)
        val result = send(admin, "/api/v1/warehouse/provenance/batches", mapper.writeValueAsString(mapOf("expectedEpoch" to 0,
            "expectedPreservationHash" to body.path("preservationHash").asString())))
        assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(201)
        assertThat(mapper.readTree(result.body()).path("batch").path("sourceCount").asInt()).isEqualTo(17)
        return mapper.readTree(result.body()).path("batch").path("id").asString()
    }
    private fun evidence(admin: String, path: String, source: JsonNode): String {
        val boundary = "legacy-effect-evidence"
        val json = mapper.writeValueAsString(mapOf("expectedEpoch" to 1, "expectedCaseHash" to source.path("sourceHash").asString(), "label" to "Bukti rekonsiliasi efek lama"))
        val bytes = ("--$boundary\r\nContent-Disposition: form-data; name=\"request\"\r\n\r\n$json\r\n--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"proof.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").toByteArray() +
            ReceiptEvidenceFixtures.pdf() + "\r\n--$boundary--\r\n".toByteArray()
        val result = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path/evidence"))
            .header("Authorization", "Bearer $admin").header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Content-Type", "multipart/form-data; boundary=$boundary").timeout(Duration.ofSeconds(25))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString())
        assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(201)
        return mapper.readTree(result.body()).path("id").asString()
    }

    @Test fun `legacy effect capture checks current WO area and binds actual pending IDs without exposing payload or applying stock`() {
        val admin = setup(0)
        val old = legacy[0]
        val reader = user(admin, setOf("inventory.provenance.manage"))
        grant(admin, reader.second, listOf(old.stock.location.toString()))
        assertThat(request("GET", "/api/v1/warehouse/provenance", reader.first).status).isEqualTo(200)
        val otherArea = mapper.readTree(request("POST", "/api/areas", admin, """{"code":"OTHER","name":"Other area"}""").contentAsString).path("id").asString()
        dataSource.connection.use { writer ->
            writer.autoCommit = false
            try {
                val pid = writer.createStatement().use { sql ->
                    sql.execute("SET LOCAL app.tenant_id='${old.stock.tenant}'")
                    sql.execute("SELECT epoch FROM inventory_tenant_cutover WHERE tenant_id='${old.stock.tenant}' FOR SHARE")
                    sql.execute("UPDATE work_order SET area_id='$otherArea' WHERE id='${old.workOrder}'")
                    sql.executeQuery("SELECT pg_backend_pid()").use { rows -> rows.next(); rows.getInt(1) }
                }
                val report = client.sendAsync(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/warehouse/provenance"))
                    .header("Authorization", "Bearer ${reader.first}").timeout(Duration.ofSeconds(25)).GET().build(), HttpResponse.BodyHandlers.ofString())
                awaitBlocked(pid)
                assertThat(report.isDone).isFalse()
                writer.commit()
                val denied = report.get(25, TimeUnit.SECONDS)
                assertThat(denied.statusCode()).isEqualTo(404)
                assertThat(denied.body()).doesNotContain("sourceCount", "LEGACY-WO")
            } finally { writer.rollback() }
        }
        assertThat(request("GET", "/api/v1/warehouse/provenance/cases?sourceTable=fulfillment_outbox", reader.first).status).isEqualTo(404)
        database.ownerFixture { connection -> connection.createStatement().use { sql ->
            sql.execute("SET app.tenant_id='${old.stock.tenant}'")
            sql.execute("UPDATE work_order SET area_id='${area(admin)}' WHERE id='${old.workOrder}'")
        } }
        val batch = begin(admin)
        val prefix = "${old.stock.tenant}/warehouse/migrations/$batch/"
        try {
            val response = request("GET", "/api/v1/warehouse/provenance/cases", admin)
            assertThat(response.contentAsString).doesNotContain(old.request(0).encode(), "Original manual resolution", "password_hash", "claimedBy")
            val cases = mapper.readTree(response.contentAsString).path("items").asSequence().toList()
            for ((sourceId, kind) in listOf(old.ids[0] to "CANCEL_PENDING", old.outbox[0] to "CANCEL_PENDING", old.ids[1] to "PROVENANCE_ONLY")) {
                val source = cases.single { it.path("sourceId").asString() == sourceId.toString() }
                assertThat(source.path("workOrder").path("code").asString()).isEqualTo("LEGACY-WO")
                val path = "/api/v1/warehouse/provenance/batches/$batch/cases/${source.path("id").asString()}"
                val file = evidence(admin, path, source)
                fun body(kind: String) = mapper.writeValueAsString(mapOf("expectedEpoch" to 1, "expectedCaseHash" to source.path("sourceHash").asString(),
                    "expectedResolutionRevision" to 0, "kind" to kind, "reason" to "Tinjauan sumber dan bukti lama", "evidenceIds" to listOf(file)))
                val wrong = if (kind == "CANCEL_PENDING") "PROVENANCE_ONLY" else "CANCEL_PENDING"
                assertThat(send(admin, "$path/resolutions", body(wrong)).statusCode()).isEqualTo(409)
                val result = send(admin, "$path/resolutions", body(kind))
                assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(201)
            }
            val fixture = fixture(admin)
            fixture.transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_provenance_case WHERE source_table IN ('fulfillment_checkpoint','fulfillment_outbox')")).isEqualTo("6")
                assertThat(scalar("SELECT state FROM fulfillment_checkpoint WHERE id='${old.ids[0]}'")).isEqualTo("DISPATCHED")
                assertThat(scalar("SELECT count(*) FROM inventory_migration_resolution")).isEqualTo("3")
                assertThat(scalar("SELECT count(*) FROM inventory_document")).isEqualTo("0")
            }
            val coordinator = context.getBean(FulfillmentCoordinator::class.java)
            repeat(2) {
                val outcome = TenantContext.runAs(old.stock.tenant) { coordinator.process(old.request(0)) }
                assertThat(outcome.state).isEqualTo(FulfillmentState.REQUIRES_RECONCILIATION)
                assertThat(outcome.outcome).isEqualTo("FULFILLMENT_SNAPSHOT_REQUIRED")
            }
            fixture.transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("2")
                assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE warehouse_admission='VERIFIED'")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress WHERE fulfillment_id='${old.ids[0]}'")).isEqualTo("0")
                assertThat(scalar("SELECT source_snapshot->>'state' FROM inventory_provenance_case WHERE source_id='${old.ids[0]}'")).isEqualTo("DISPATCHED")
            }
            assertThat(request("GET", "/api/v1/warehouse/provenance/cases/${cases.first().path("id").asString()}", tenant()).status).isEqualTo(404)
        } finally { storage.list(old.stock.tenant.toString(), prefix).objects.forEach { storage.delete(it.key) } }
    }

    @Test fun `manual and applied legacy outcomes survive fresh transaction redelivery and reconciliation cannot reopen manual work`() {
        val admin = setup(1)
        val old = legacy[1]
        begin(admin)
        val coordinator = context.getBean(FulfillmentCoordinator::class.java)
        TenantContext.runAs(old.stock.tenant) {
            repeat(2) {
                val manual = coordinator.process(old.request(2))
                assertThat(manual.state).isEqualTo(FulfillmentState.MANUAL_RESOLVED)
                assertThat(manual.replayed).isTrue()
                assertThat(manual.outcome).isEqualTo("Original manual resolution")
                assertThat(coordinator.accept(old.request(2)).state).isEqualTo(FulfillmentState.MANUAL_RESOLVED)
                assertThat(coordinator.process(old.request(1)).state).isEqualTo(FulfillmentState.APPLIED)
            }
            val outbox = context.getBean(FulfillmentOutboxRepository::class.java)
            val worker = UUID.randomUUID().toString()
            val now = Instant.now()
            val deliveries = List(3) { requireNotNull(outbox.claimPending(old.stock.tenant, worker, now, now.plusSeconds(60))) }
            outbox.reconcile(deliveries.single { it.id == old.outbox[2] }, "Late worker uncertainty")
            dataSource.connection.use { cutoff ->
                cutoff.autoCommit = false
                try {
                    val pid = cutoff.createStatement().use { sql ->
                        sql.execute("SET LOCAL app.tenant_id='${old.stock.tenant}'")
                        sql.execute("SELECT epoch FROM inventory_tenant_cutover WHERE tenant_id='${old.stock.tenant}' FOR UPDATE")
                        sql.executeQuery("SELECT pg_backend_pid()").use { rows -> rows.next(); rows.getInt(1) }
                    }
                    val ack = CompletableFuture.runAsync { TenantContext.runAs(old.stock.tenant) { outbox.markOutboxConsumed(old.outbox[2], worker) } }
                    awaitBlocked(pid)
                    assertThat(ack.isDone).isFalse()
                    cutoff.commit()
                    ack.get(25, TimeUnit.SECONDS)
                } finally { cutoff.rollback() }
            }
        }
        fixture(admin).transaction {
            assertThat(scalar("SELECT state||':'||outcome FROM fulfillment_checkpoint WHERE id='${old.ids[2]}'")).isEqualTo("MANUAL_RESOLVED:Original manual resolution")
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress WHERE fulfillment_id='${old.ids[2]}'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM inventory_document")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM fulfillment_outbox WHERE id='${old.outbox[2]}' AND published_at IS NOT NULL")).isEqualTo("1")
        }
    }

    private fun awaitBlocked(pid: Int) {
        dataSource.connection.use { observer -> await().atMost(Duration.ofSeconds(15)).until { observer.createStatement().use { sql ->
            sql.executeQuery("SELECT EXISTS(SELECT FROM pg_stat_activity WHERE $pid=ANY(pg_blocking_pids(pid)))").use { rows -> rows.next(); rows.getBoolean(1) }
        } } }
    }
    @Test fun `cutoff closes new legacy effects and identity changes while old terminal delivery and ACK remain usable`() {
        val admin = setup(2)
        val old = legacy[2]
        begin(admin)
        val coordinator = context.getBean(FulfillmentCoordinator::class.java)
        val creation = runCatching { TenantContext.runAs(old.stock.tenant) {
            coordinator.accept(old.request(0).copy(operationKey = UUID.randomUUID().toString()))
        } }.exceptionOrNull()
        assertThat(creation).isNotNull()
        assertThat(generateSequence(requireNotNull(creation)) { it.cause }.filterIsInstance<java.sql.SQLException>().first().sqlState).isEqualTo("23514")
        val fixture = fixture(admin)
        for ((command, expected) in listOf(
            "UPDATE fulfillment_checkpoint SET source='MIGRATION' WHERE id='${old.ids[0]}'" to "23514",
            "UPDATE fulfillment_checkpoint SET required_effects='WORK_ORDER' WHERE id='${old.ids[0]}'" to "23514",
            "UPDATE fulfillment_checkpoint SET state='READY' WHERE id='${old.ids[1]}'" to "23514",
            "UPDATE fulfillment_outbox SET payload_hash='${"b".repeat(64)}' WHERE id='${old.outbox[0]}'" to "23514",
            "INSERT INTO fulfillment_outbox(id,tenant_id,fulfillment_id,sequence,event_type,payload_hash,payload) VALUES ('${UUID.randomUUID()}','${old.stock.tenant}','${old.ids[0]}',2,'FULFILLMENT_APPLY','${"a".repeat(64)}','{}')" to "23514",
            "INSERT INTO fulfillment_effect_progress(id,tenant_id,fulfillment_id,effect_type,status) VALUES ('${UUID.randomUUID()}','${old.stock.tenant}','${old.ids[0]}','INVENTORY','STARTED')" to "23514",
            "DELETE FROM fulfillment_checkpoint WHERE id='${old.ids[0]}'" to "23514",
            "INSERT INTO inventory_serial_tombstone(id,tenant_id,serial_number) VALUES ('${UUID.randomUUID()}','${old.stock.tenant}','FORGED')" to "42501",
            "DELETE FROM inventory_serial_tombstone" to "42501",
        )) {
            val failure = runCatching { fixture.transaction { sql(command) } }.exceptionOrNull()
            assertThat(failure).isNotNull()
            assertThat(generateSequence(requireNotNull(failure)) { it.cause }.filterIsInstance<java.sql.SQLException>().first().sqlState).isEqualTo(expected)
        }
        TenantContext.runAs(old.stock.tenant) {
            assertThat(coordinator.accept(old.request(0)).state).isEqualTo(FulfillmentState.DISPATCHED)
            assertThat(coordinator.process(old.request(0)).state).isEqualTo(FulfillmentState.REQUIRES_RECONCILIATION)
            assertThat(coordinator.accept(old.request(1)).state).isEqualTo(FulfillmentState.APPLIED)
            assertThat(coordinator.accept(old.request(2)).outcome).isEqualTo("Original manual resolution")
            val outbox = context.getBean(FulfillmentOutboxRepository::class.java)
            val delivery = requireNotNull(outbox.claimPending(old.stock.tenant, "legacy-ack", Instant.now(), Instant.now().plusSeconds(60)))
            outbox.markOutboxConsumed(delivery.id, delivery.claimedBy)
        }
        fixture.transaction {
            assertThat(scalar("SELECT count(*) FROM fulfillment_checkpoint")).isEqualTo("3")
            assertThat(scalar("SELECT count(*) FROM fulfillment_outbox")).isEqualTo("3")
            assertThat(scalar("SELECT count(*) FROM fulfillment_outbox WHERE published_at IS NOT NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_document")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("2")
        }
    }

}
