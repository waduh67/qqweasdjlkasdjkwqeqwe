package com.duluin.ftth.inventory

import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.fulfillment.*
import java.time.Instant
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.MethodOrderer
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
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Import(ReceiptRealStorage::class, WarehouseMigrationOpeningApprovalIT.Configuration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseMigrationOpeningApprovalIT : WarehouseApprovalHttpFixture() {
    companion object {
        private var restart: Restart? = null
        private val legacy = List(5) { WarehouseMigrationLegacyFixture() }
        private val checkpoints = List(2) { UUID.randomUUID() }
        private val messages = List(2) { UUID.randomUUID() }
        private val orders = List(2) { UUID.randomUUID() }
        private fun legacyRequest(index: Int) = FulfillmentRequest(legacy[index].tenant, "legacy.opening", checkpoints[index].toString(),
            "a".repeat(64), FulfillmentSource.WORK_ORDER, orders[index], null, orders[index], "REPAIR", true,
            setOf(FulfillmentEffectType.INVENTORY, FulfillmentEffectType.WORK_ORDER))
        private val database = WarehouseSchemaDatabase("172").also { db -> db.ownerFixture { connection ->
            legacy.forEach { it.seed(connection) }
            repeat(2) { index ->
                val old = legacy[index]
                val actor = UUID.randomUUID()
                connection.createStatement().use { sql ->
                    sql.execute("INSERT INTO app_user(id,tenant_id,email,name,password_hash) VALUES ('$actor','${old.tenant}','$actor@example.test','Legacy actor','unused')")
                    sql.execute("""INSERT INTO work_order(id,tenant_id,code,type,title,status,customer_id,created_by)
                        VALUES ('${orders[index]}','${old.tenant}','LEGACY-EFFECT','REPAIR','Pending legacy work','IN_PROGRESS','${old.customer}','$actor')""")
                    sql.execute("""INSERT INTO fulfillment_checkpoint(id,tenant_id,namespace,operation_key,canonical_hash,source,target_id,state,
                        checkpoint_updated_at,work_order_id,work_order_kind,required_effects) VALUES ('${checkpoints[index]}','${old.tenant}',
                        'legacy.opening','${checkpoints[index]}','${"a".repeat(64)}','WORK_ORDER','${orders[index]}','DISPATCHED',now(),
                        '${orders[index]}','REPAIR','INVENTORY,WORK_ORDER')""")
                }
                connection.prepareStatement("""INSERT INTO fulfillment_outbox(id,tenant_id,fulfillment_id,sequence,event_type,payload_hash,payload)
                    VALUES (?,?,?,1,'FULFILLMENT_APPLY',?,?)""").use { sql ->
                    sql.setObject(1,messages[index]); sql.setObject(2,old.tenant); sql.setObject(3,checkpoints[index])
                    sql.setString(4,"a".repeat(64)); sql.setString(5,legacyRequest(index).encode()); sql.executeUpdate()
                }
            }
        } }
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
    @Autowired private lateinit var inbox: WarehouseInboxApi
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private data class Batch(val token: String, val actor: String, val old: WarehouseMigrationLegacyFixture, val id: String,
        val cases: List<JsonNode>, val cable: String, val serial: String) {
        fun case(sourceId: UUID) = cases.single { it.path("sourceId").asString() == sourceId.toString() }
        fun path(source: JsonNode) = "/api/v1/warehouse/provenance/batches/$id/cases/${source.path("id").asString()}"
    }

    private fun setup(index: Int): Batch {
        val old = legacy[index]
        val token = tenant("migration-${old.tenant}")
        val actor = mapper.readTree(request("GET", "/api/me", token).contentAsString).path("id").asString()
        database.ownerFixture { connection -> connection.createStatement().use { sql ->
            sql.execute("SET app.tenant_id='${old.tenant}'")
            sql.execute("UPDATE inventory_location SET name='Gudang lama',area_id='${area(token)}',revision=revision+1 WHERE id='${old.location}'")
            if (index == 4) sql.execute("UPDATE inventory_serialized_asset SET serial_number='MOVED-AWAY',mac_address='55:66:77:88:99:AA',revision=revision+1 WHERE id='${old.second}'")
            if (index < 2) sql.execute("UPDATE work_order SET area_id='${area(token)}' WHERE id='${orders[index]}'")
            sql.execute("UPDATE customer SET area_id='${area(token)}' WHERE id='${old.customer}'")
        } }
        grant(token, actor, listOf(old.location.toString()))
        val cable = create("skus", token, """{"code":"CABLE","name":"Kabel lama","tracking":"LOT","baseUnit":"MM"}""").path("id").asString()
        val serial = create("skus", token, """{"code":"ONU","name":"ONU lama","tracking":"SERIAL","baseUnit":"EA"}""").path("id").asString()
        val report = request("GET", "/api/v1/warehouse/provenance", token)
        assertThat(report.status).withFailMessage(report.contentAsString).isEqualTo(200)
        val begin = send(token, "/api/v1/warehouse/provenance/batches", mapper.writeValueAsString(mapOf("expectedEpoch" to 0,
            "expectedPreservationHash" to mapper.readTree(report.contentAsString).path("preservationHash").asString())))
        assertThat(begin.statusCode()).withFailMessage(begin.body()).isEqualTo(201)
        val cases = mapper.readTree(request("GET", "/api/v1/warehouse/provenance/cases", token).contentAsString).path("items").asSequence().toList()
        return Batch(token, actor, old, mapper.readTree(begin.body()).path("batch").path("id").asString(), cases, cable, serial)
    }

    private fun evidence(batch: Batch, source: JsonNode, expectedStatus: Int = 201): String {
        val boundary = "migration-resolution-file"
        val json = mapper.writeValueAsString(mapOf("expectedEpoch" to 1, "expectedCaseHash" to source.path("sourceHash").asString(), "label" to "Bukti pemeriksaan fisik"))
        val bytes = ("--$boundary\r\nContent-Disposition: form-data; name=\"request\"\r\n\r\n$json\r\n--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"proof.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").toByteArray() +
            ReceiptEvidenceFixtures.pdf() + "\r\n--$boundary--\r\n".toByteArray()
        val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port${batch.path(source)}/evidence"))
            .header("Authorization", "Bearer ${batch.token}").header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Content-Type", "multipart/form-data; boundary=$boundary").timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(expectedStatus)
        return if (expectedStatus == 201) mapper.readTree(response.body()).path("id").asString() else ""
    }

    private fun body(source: JsonNode, file: String, kind: String = "PROVENANCE_ONLY", revision: Long = 0,
        stock: Map<String, String>? = null, duplicate: String? = null, reason: String = "Pemeriksaan data dan bukti asli") =
        mapper.writeValueAsString(mapOf("expectedEpoch" to 1, "expectedCaseHash" to source.path("sourceHash").asString(),
            "expectedResolutionRevision" to revision, "kind" to kind, "reason" to reason, "evidenceIds" to listOf(file),
            "stock" to stock, "duplicateCaseId" to duplicate))
    private fun stock(sku: String, unit: String, owner: String = "ISP") = mapOf("skuId" to sku, "sourceUnit" to unit, "legalOwner" to owner)
    private fun command(token: String, path: String, body: String, key: String) = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        .header("Authorization", "Bearer $token").header("Idempotency-Key", key).header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(25)).POST(HttpRequest.BodyPublishers.ofString(body)).build()
    private fun send(token: String, path: String, body: String, key: String = UUID.randomUUID().toString()) =
        client.send(command(token, path, body, key), HttpResponse.BodyHandlers.ofString())
    private fun cleanup(batch: Batch) {
        val prefix = "${batch.old.tenant}/warehouse/migrations/${batch.id}/"
        storage.list(batch.old.tenant.toString(), prefix).objects.forEach { storage.delete(it.key) }
    }

    private fun runInFailure(batch: Batch, command: WarehousePostingFixture.() -> Unit): String {
        val failure = runCatching { fixture(batch.token).transaction { command() } }.exceptionOrNull()
        assertThat(failure).isNotNull()
        return generateSequence(requireNotNull(failure)) { it.cause }.filterIsInstance<java.sql.SQLException>().first().sqlState
    }

    private fun review(batch: Batch): JsonNode {
        val response = request("GET", "/api/v1/warehouse/provenance/batches/${batch.id}/review", batch.token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }
    private fun openingInput(batch: Batch, review: JsonNode = review(batch)): String {
        val location = mapper.readTree(request("GET", "/api/v1/warehouse/locations/${batch.old.location}", batch.token).contentAsString)
        return mapper.writeValueAsString(mapOf("expectedEpoch" to 1, "expectedReviewHash" to review.path("reviewHash").asString(),
            "reviewLocationId" to batch.old.location, "expectedReviewLocationRevision" to location.path("revision").asLong(),
            "migrationReference" to "Catatan stok sebelum migrasi", "reason" to "Hasil pemeriksaan fisik dan riwayat asli"))
    }
    private fun resolve(batch: Batch, id: UUID, kind: String, stock: Map<String, String>? = null, duplicate: String? = null,
        revision: Long = 0): String {
        val source = batch.case(id)
        val file = evidence(batch, source)
        val response = send(batch.token, batch.path(source) + "/resolutions", body(source, file, kind, revision, stock, duplicate))
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201)
        return file
    }
    private fun ready(batch: Batch): String {
        resolve(batch, batch.old.first, "BASELINE_STOCK", stock(batch.serial, "EA"))
        if (batch.old === legacy[4]) resolve(batch, batch.old.second, "PROVENANCE_ONLY")
        else resolve(batch, batch.old.second, "DUPLICATE", duplicate = batch.case(batch.old.first).path("id").asString())
        val balanceFile = resolve(batch, batch.old.balance, "BASELINE_STOCK", stock(batch.cable, "MM"))
        resolve(batch, batch.old.pending, "CANCEL_PENDING")
        val index = legacy.indexOf(batch.old)
        if (index < 2) {
            resolve(batch, checkpoints[index], "CANCEL_PENDING")
            resolve(batch, messages[index], "CANCEL_PENDING")
        }
        assertThat(review(batch).path("issues").size()).isZero()
        return balanceFile
    }

    class FailureProbe : WarehouseApprovalProbe {
        val stage = java.util.concurrent.atomic.AtomicReference<WarehouseApprovalStage?>()
        val lastError = java.util.concurrent.atomic.AtomicReference<String?>()
        override fun reached(stage: WarehouseApprovalStage, requestId: UUID) {
            if (this.stage.get() == stage) throw WarehouseContractException(WarehouseError(WarehouseErrorCode.STALE_REVISION, "Injected opening rollback"))
        }
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @org.springframework.context.annotation.Bean fun openingFailureProbe() = FailureProbe()
        @org.springframework.context.annotation.Bean fun openingDiagnostics(probe: FailureProbe) = object : org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
            override fun extendHandlerExceptionResolvers(resolvers: MutableList<org.springframework.web.servlet.HandlerExceptionResolver>) {
                resolvers.add(0, org.springframework.web.servlet.HandlerExceptionResolver { _, _, _, exception ->
                    val cause = generateSequence<Throwable>(exception) { it.cause }.last()
                    // Keep diagnostic context in private test output, never in the HTTP response.
                    probe.lastError.set(cause.javaClass.simpleName + ": " + cause.message?.lineSequence()?.firstOrNull() +
                        " at " + cause.stackTrace.firstOrNull())
                    null
                })
            }
        }
    }
    @Autowired private lateinit var failure: FailureProbe
    private fun diagnostic(response: HttpResponse<String>) = response.body() + "\n" + failure.lastError.get().orEmpty()

    private fun tiers(token: String, place: String, count: Int): List<Pair<String, String>> {
        val approvers = List(count) { approver(token, listOf(place)) }
        configure(token, mapper.writeValueAsString(mapOf("expectedRevision" to 0, "currency" to "IDR", "expiryHours" to 24,
            "warehouseIds" to listOf(place), "rules" to listOf(mapOf("operation" to "OPENING_BALANCE", "tiers" to approvers.mapIndexed { index, actor ->
                mapOf("minimumMinor" to ((index + 1L) * 100000000).toString(), "userIds" to listOf(actor.second), "roleIds" to emptyList<String>())
            })))))
        return approvers
    }
    private fun seal(batch: Batch): String {
        val result = send(batch.token, "/api/v1/warehouse/provenance/batches/${batch.id}/opening", openingInput(batch))
        assertThat(result.statusCode()).withFailMessage(diagnostic(result)).isEqualTo(201)
        return mapper.readTree(result.body()).path("id").asString()
    }
    private fun pending(token: String, document: String): String {
        val source = request("GET", "/api/v1/warehouse/approvals/sources/$document", token)
        assertThat(source.status).withFailMessage(source.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(source.contentAsString).path("canRequest").asBoolean()).withFailMessage(source.contentAsString).isTrue()
        val result = send(token, "/api/v1/warehouse/approvals/request", mapper.writeValueAsString(mapOf("sourceDocumentId" to document, "sourceRevision" to 0)))
        assertThat(result.statusCode()).withFailMessage(diagnostic(result)).isEqualTo(201)
        return mapper.readTree(result.body()).path("requestId").asString()
    }
    private fun decide(token: String, approval: String, revision: Long = 0, key: String = UUID.randomUUID().toString(), action: String = "APPROVE") =
        send(token, "/api/v1/warehouse/approvals/decide", decision(approval, revision, action, if (action == "REJECT") "Pemeriksaan ulang diperlukan" else null), key)

    private fun finalizationInput(batch: String, document: String, hash: String) = mapper.writeValueAsString(mapOf(
        "expectedEpoch" to 1, "openingDocumentId" to document, "expectedReviewHash" to hash,
        "reason" to "Saldo awal dan riwayat sudah diperiksa independen"))

    private fun finalized(token: String, batch: String, document: String): Pair<String, String> {
        val path = "/api/v1/warehouse/provenance/batches/$batch/finalization"
        val review = request("GET", path, token)
        assertThat(review.status).withFailMessage(review.contentAsString).isEqualTo(200)
        val snapshot = mapper.readTree(review.contentAsString)
        assertThat(snapshot.path("issues").size()).withFailMessage(review.contentAsString).isZero()
        val body = finalizationInput(batch, document, snapshot.path("reviewHash").asString())
        val key = UUID.randomUUID().toString()
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("batchId" to batch, "input" to mapper.readTree(body))))
        assertThatThrownBy { fixture(token).transaction {
            val actor = scalar("SELECT actor_id FROM inventory_migration_opening_request WHERE id='$document'")
            jdbc { connection -> connection.prepareStatement("SELECT warehouse_finalize_migration(?,?,?,?,?)").use {
                it.setObject(1, UUID.fromString(batch)); it.setObject(2, UUID.fromString(actor)); it.setLong(3, 0)
                it.setString(4, key); it.setString(5, canonical.json)
                it.executeQuery().use { rows -> assertThat(rows.next()).isTrue() }
            } }
            error("Injected finalization transaction rollback")
        } }.hasStackTraceContaining("Injected finalization transaction rollback")
        fixture(token).transaction {
            assertThat(scalar("SELECT state||':'||epoch FROM inventory_tenant_cutover")).isEqualTo("VALIDATING:1")
            assertThat(scalar("SELECT count(*) FROM inventory_migration_finalization")).isEqualTo("0")
        }
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val responses = java.util.concurrent.Executors.newFixedThreadPool(2).use { pool ->
            List(2) { pool.submit<HttpResponse<String>> {
                barrier.await(10, TimeUnit.SECONDS)
                send(token, path, body, key)
            } }.map { it.get(30, TimeUnit.SECONDS) }
        }
        responses.forEach { assertThat(it.statusCode()).withFailMessage(diagnostic(it)).isEqualTo(200) }
        assertThat(responses[0].body()).isEqualTo(responses[1].body())
        assertThat(send(token, path, body.replace("independen", "ulang"), key).statusCode()).isEqualTo(409)
        assertThat(send(token, path, body).statusCode()).isEqualTo(409)
        assertThat(send(token, path, body.dropLast(1) + ",\"actorId\":\"${UUID.randomUUID()}\"}", key).statusCode()).isEqualTo(400)
        assertThat(request("GET", path + "?unknown=true", token).status).isEqualTo(400)
        fixture(token).transaction {
            assertThat(scalar("SELECT state||':'||epoch FROM inventory_tenant_cutover")).isEqualTo("ENFORCED:2")
            assertThat(scalar("SELECT count(*) FROM inventory_migration_finalization")).isEqualTo("1")
        }
        return key to responses[0].body()
    }

    @Test fun `independent all-tier approval admits exact original physical identity and one competing baseline wins`() {
        val batch = setup(0)
        try {
            ready(batch)
            val first = seal(batch)
            val second = seal(batch)
            val actors = tiers(batch.token, batch.old.location.toString(), 3)
            val approvals = listOf(pending(batch.token, first), pending(batch.token, second))
            assertThatThrownBy { fixture(batch.token).transaction {
                sql("SELECT warehouse_admit_migration_opening('$first','${approvals[0]}','${UUID.randomUUID()}')")
            } }.hasStackTraceContaining("opening admission requires current independent approval and exact source")
            assertThatThrownBy { fixture(batch.token).transaction {
                sql("INSERT INTO inventory_migration_admission SELECT * FROM inventory_migration_admission WHERE false")
            } }.hasStackTraceContaining("permission denied for table inventory_migration_admission")
            val details = request("GET", "/api/v1/warehouse/approvals/${approvals[0]}/details", actors[0].first)
            assertThat(details.status).withFailMessage(details.contentAsString).isEqualTo(200)
            val view = mapper.readTree(details.contentAsString)
            assertThat(view.path("document").path("migration").path("baselineCount").asInt()).isEqualTo(2)
            assertThat(view.path("document").path("migration").path("valuation").asString()).isEqualTo("UNKNOWN")
            assertThat(view.path("document").path("lines").asSequence().map { it.path("serial").asString() }.toList()).contains(" Serial-A ")
            assertThat(view.path("actions").path("canDecide").asBoolean()).isTrue()
            assertThat(view.path("cost").isMissingNode).isTrue()
            val cases = request("GET", "/api/v1/warehouse/approvals/${approvals[0]}/migration-cases?size=2", actors[0].first)
            assertThat(cases.status).isEqualTo(200)
            assertThat(mapper.readTree(cases.contentAsString).path("totalElements").asInt()).isEqualTo(13)
            assertThat(mapper.readTree(cases.contentAsString).path("items").size()).isEqualTo(2)
            val attachments = request("GET", "/api/v1/warehouse/approvals/${approvals[0]}/attachments", actors[0].first)
            assertThat(attachments.status).isEqualTo(200)
            assertThat(attachments.contentAsString).doesNotContain("objectKey", "storageKey", "/warehouse/migrations/")
            val files = mapper.readTree(attachments.contentAsString).path("items").asSequence().toList()
            assertThat(files).hasSize(6)
            for (file in files) {
                val download = request("GET", "/api/v1/warehouse/approvals/${approvals[0]}/attachments/${file.path("id").asString()}", actors[0].first)
                assertThat(download.status).isEqualTo(200)
                assertThat(download.contentAsByteArray).isEqualTo(ReceiptEvidenceFixtures.pdf())
            }
            assertThat(request("GET", "/api/v1/warehouse/provenance/batches/${batch.id}/review", actors[0].first).status).isEqualTo(403)
            for (approval in approvals) for (tier in 0..1) {
                val result = decide(actors[tier].first, approval, tier.toLong())
                assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(200)
            }
            fixture(batch.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_migration_admission")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE warehouse_admission='VERIFIED'")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_approval WHERE value_numerator IS NULL AND value_denominator IS NULL AND currency IS NULL")).isEqualTo("2")
            }
            val drift = runInFailure(batch) {
                sql("UPDATE fulfillment_checkpoint SET required_effects='WORK_ORDER' WHERE id='${checkpoints[0]}'")
            }
            assertThat(drift).isEqualTo("23514")
            fixture(batch.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_migration_admission")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM warehouse_migration_cancellation_receipt")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_approval WHERE status='PENDING' AND revision=2")).isEqualTo("2")
            }
            val outbox = context.getBean(FulfillmentOutboxRepository::class.java)
            val delivery = TenantContext.runAs(batch.old.tenant) { outbox.claimPending(batch.old.tenant, "legacy-worker", Instant.now(), Instant.now().plusSeconds(300)) }
                ?: error(fixture(batch.token).transaction { scalar("""SELECT 'Legacy lease unavailable: published='||(published_at IS NOT NULL)::text||
                    ', leased='||(lease_until IS NOT NULL)::text||', checkpoint='||(SELECT state FROM fulfillment_checkpoint WHERE id=fulfillment_id)
                    FROM fulfillment_outbox WHERE id='${messages[0]}'""") })
            val keys = List(2) { UUID.randomUUID().toString() }
            val outcomes = approvals.mapIndexed { index, approval -> client.sendAsync(command(actors[2].first,
                "/api/v1/warehouse/approvals/decide", decision(approval, 2), keys[index]), HttpResponse.BodyHandlers.ofString()) }.map { it.get(25, TimeUnit.SECONDS) }
            assertThat(outcomes.map { it.statusCode() }.sorted()).withFailMessage(outcomes.joinToString { it.body() }).isEqualTo(listOf(200, 409))
            val winner = outcomes.indexOfFirst { it.statusCode() == 200 }
            assertThat(decide(actors[2].first, approvals[winner], 2, keys[winner]).body()).isEqualTo(outcomes[winner].body())
            fixture(batch.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_migration_admission")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='OPENING_BALANCE' AND state='APPLIED'")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_approval_effect")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_approval WHERE status='STALE'")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE warehouse_admission='VERIFIED' AND status='AVAILABLE' AND quantity_base IN (1,82500)")).isEqualTo("2")
                assertThat(scalar("SELECT warehouse_admission||':'||serial_number FROM inventory_serialized_asset WHERE id='${batch.old.first}'")).isEqualTo("VERIFIED: Serial-A ")
                assertThat(scalar("SELECT warehouse_admission||':'||serial_number FROM inventory_serialized_asset WHERE id='${batch.old.second}'")).isEqualTo("LEGACY_UNRESOLVED:serial-a")
                assertThat(scalar("SELECT quantity FROM inventory_balance_projection WHERE id='${batch.old.balance}'")).isEqualTo("82500")
                assertThat(scalar("SELECT count(*) FROM inventory_lot WHERE supplier_id IS NULL AND cost_total_minor IS NULL")).isEqualTo("1")
                assertThat(scalar("SELECT state FROM inventory_tenant_cutover")).isEqualTo("VALIDATING")
                assertThat(scalar("SELECT count(*) FROM inventory_migration_cancellation")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM fulfillment_migration_cancellation")).isEqualTo("2")
                assertThat(scalar("SELECT state FROM fulfillment_checkpoint WHERE id='${checkpoints[0]}'")).isEqualTo("DISPATCHED")
                assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress")).isEqualTo("0")
            }
            TenantContext.runAs(batch.old.tenant) {
                val coordinator = context.getBean(FulfillmentCoordinator::class.java)
                repeat(2) {
                    val replay = coordinator.process(legacyRequest(0))
                    assertThat(replay.state).isEqualTo(FulfillmentState.MANUAL_RESOLVED)
                    assertThat(replay.replayed).isTrue()
                    assertThat(replay.outcome).isEqualTo("CANCELED_BY_APPROVED_MIGRATION")
                    assertThat(coordinator.accept(legacyRequest(0))).isEqualTo(replay)
                    outbox.markOutboxConsumed(delivery.id, delivery.claimedBy)
                    assertThat(outbox.reconcile(delivery, "Delayed failure").state).isEqualTo(FulfillmentState.MANUAL_RESOLVED)
                }
                assertThat(outbox.claimPending(batch.old.tenant, "new-worker", Instant.now().plusSeconds(600), Instant.now().plusSeconds(900))).isNull()
            }
            fixture(batch.token).transaction {
                assertThat(scalar("SELECT count(*) FROM fulfillment_outbox WHERE published_at IS NULL AND claimed_by='legacy-worker'")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='OPENING_BALANCE'")).isEqualTo("1")
            }
            val closedCase = batch.case(batch.old.balance)
            evidence(batch, closedCase, 409)
            val history = request("GET", batch.path(closedCase) + "/resolutions", batch.token)
            assertThat(history.status).isEqualTo(200)
            val original = mapper.readTree(history.contentAsString).path("items").path(0)
            val file = original.path("evidence").path(0).path("id").asString()
            val revised = send(batch.token, batch.path(closedCase) + "/resolutions", body(closedCase, file, revision = 1))
            assertThat(revised.statusCode()).withFailMessage(revised.body()).isEqualTo(409)
            assertThat(mapper.readTree(revised.body()).path("code").asString()).isEqualTo("SOURCE_NOT_VERIFIED")
            assertThat(request("GET", batch.path(closedCase) + "/resolutions", batch.token).contentAsString).isEqualTo(history.contentAsString)
            for (command in listOf("UPDATE fulfillment_checkpoint SET state='READY' WHERE id='${checkpoints[0]}'",
                "UPDATE fulfillment_outbox SET claimed_by=NULL,lease_until=NULL WHERE id='${messages[0]}'",
                "UPDATE fulfillment_checkpoint SET id='${UUID.randomUUID()}' WHERE id='${checkpoints[0]}'",
                "INSERT INTO fulfillment_effect_progress(id,tenant_id,fulfillment_id,effect_type,status) VALUES ('${UUID.randomUUID()}','${batch.old.tenant}','${checkpoints[0]}','INVENTORY','STARTED')",
                "SELECT fulfillment_cancel_migration_effects('${listOf(first, second)[winner]}','${UUID.randomUUID()}')",
                "DELETE FROM fulfillment_migration_cancellation")) {
                val denied = runCatching { fixture(batch.token).transaction { sql(command) } }.exceptionOrNull()
                assertThat(denied).isNotNull()
                assertThat(generateSequence(requireNotNull(denied)) { it.cause }.filterIsInstance<java.sql.SQLException>().first().sqlState).isIn("23514", "42501")
            }
            val completed = finalized(batch.token, batch.id, listOf(first, second)[winner])
            assertThat(mapper.readTree(completed.second).path("cancellationCount").asInt()).isEqualTo(3)
        } finally { cleanup(batch) }
    }

    @Test fun `failure after physical admission rolls back original assets claims lot stock and final decision then same key succeeds`() {
        val batch = setup(1)
        try {
            ready(batch)
            val document = seal(batch)
            val actor = tiers(batch.token, batch.old.location.toString(), 1).single()
            val approval = pending(batch.token, document)
            val key = UUID.randomUUID().toString()
            for (stage in listOf(WarehouseApprovalStage.OWNER_EFFECT, WarehouseApprovalStage.EFFECT_RECEIPT, WarehouseApprovalStage.RESPONSE)) {
                failure.stage.set(stage)
                try {
                    val result = decide(actor.first, approval, key = key)
                    assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(409)
                    fixture(batch.token).transaction {
                        for (table in listOf("inventory_migration_admission", "inventory_migration_admission_line", "inventory_migration_cancellation", "fulfillment_migration_cancellation", "inventory_lot", "inventory_segment", "inventory_approval_decision", "inventory_approval_effect", "inventory_outbox", "inventory_inbox"))
                            assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo("0")
                        assertThat(scalar("SELECT count(*) FROM inventory_identity_claim WHERE state='ADMITTED'")).isEqualTo("0")
                        assertThat(scalar("SELECT count(*) FROM inventory_serialized_asset WHERE warehouse_admission='VERIFIED'")).isEqualTo("0")
                        assertThat(scalar("SELECT status||':'||revision FROM inventory_approval")).isEqualTo("PENDING:0")
                        assertThat(scalar("SELECT count(*) FROM inventory_document_line WHERE stock_identity_id IS NOT NULL")).isEqualTo("0")
                    }
                } finally { failure.stage.set(null) }
            }
            val result = decide(actor.first, approval, key = key)
            assertThat(result.statusCode()).withFailMessage(diagnostic(result)).isEqualTo(200)
            assertThat(decide(actor.first, approval, key = key).body()).isEqualTo(result.body())
        } finally { cleanup(batch) }
    }

    @Test fun `changed current owner area stales the sealed approval and rejection requires a new immutable request`() {
        val batch = setup(2)
        try {
            ready(batch)
            val document = seal(batch)
            val actor = tiers(batch.token, batch.old.location.toString(), 1).single()
            val approval = pending(batch.token, document)
            val response = request("POST", "/api/areas", batch.token, """{"code":"NEW-CUSTOMER","name":"Current customer area"}""")
            assertThat(response.status).isEqualTo(201)
            val addedArea = mapper.readTree(response.contentAsString).path("id").asString()
            for (id in listOf(batch.actor, actor.second)) {
                val principal = mapper.readTree(request("GET", "/api/users/$id", batch.token).contentAsString)
                assertThat(request("PUT", "/api/users/$id/access", batch.token, mapper.writeValueAsString(mapOf(
                    "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(batch.token), addedArea)))).status).isEqualTo(200)
            }
            database.ownerFixture { connection -> connection.createStatement().use { sql ->
                sql.execute("SET app.tenant_id='${batch.old.tenant}'")
                sql.execute("UPDATE customer SET area_id='$addedArea' WHERE id='${batch.old.customer}'")
            } }
            val stale = decide(actor.first, approval)
            assertThat(stale.statusCode()).withFailMessage(stale.body()).isEqualTo(409)
            assertThat(mapper.readTree(stale.body()).path("status").asString()).isEqualTo("STALE")
            val revised = seal(batch)
            val rejected = pending(batch.token, revised)
            val reject = decide(actor.first, rejected, action = "REJECT")
            assertThat(reject.statusCode()).withFailMessage(reject.body()).isEqualTo(200)
            val detail = request("GET", "/api/v1/warehouse/approvals/$rejected/details", batch.token)
            assertThat(detail.status).withFailMessage(detail.contentAsString).isEqualTo(200)
            assertThat(mapper.readTree(detail.contentAsString).path("actions").path("canRework").asBoolean()).isFalse()
            assertThat(seal(batch)).isNotEqualTo(revised)
            fixture(batch.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_migration_admission")).isEqualTo("0")
                assertThat(scalar("SELECT approval_disposition FROM inventory_document WHERE id='$revised'")).isEqualTo("REWORK_REQUIRED")
            }
        } finally { cleanup(batch) }
    }

    @Test fun `empty legacy tenant posts explicit approved zero baseline without physical SKU line or valuation`() {
        val old = legacy[3]
        val token = tenant("migration-${old.otherTenant}")
        val place = create("locations", token, """{"code":"EMPTY","name":"Empty baseline review","kind":"WAREHOUSE"}""")
        val report = mapper.readTree(request("GET", "/api/v1/warehouse/provenance", token).contentAsString)
        val begin = send(token, "/api/v1/warehouse/provenance/batches", mapper.writeValueAsString(mapOf("expectedEpoch" to 0,
            "expectedPreservationHash" to report.path("preservationHash").asString())))
        assertThat(begin.statusCode()).withFailMessage(begin.body()).isEqualTo(201)
        val batch = mapper.readTree(begin.body()).path("batch").path("id").asString()
        val review = mapper.readTree(request("GET", "/api/v1/warehouse/provenance/batches/$batch/review", token).contentAsString)
        val opened = send(token, "/api/v1/warehouse/provenance/batches/$batch/opening", mapper.writeValueAsString(mapOf(
            "expectedEpoch" to 1, "expectedReviewHash" to review.path("reviewHash").asString(), "reviewLocationId" to place.path("id").asString(),
            "expectedReviewLocationRevision" to place.path("revision").asLong(), "migrationReference" to "Confirmed no physical stock", "reason" to "No stock or pending legacy effects")))
        assertThat(opened.statusCode()).withFailMessage(opened.body()).isEqualTo(201)
        val document = mapper.readTree(opened.body()).path("id").asString()
        val actor = tiers(token, place.path("id").asString(), 1).single()
        val approval = pending(token, document)
        val approved = decide(actor.first, approval)
        assertThat(approved.statusCode()).withFailMessage(diagnostic(approved)).isEqualTo(200)
        fixture(token).transaction {
            for (table in listOf("inventory_sku", "inventory_document_line", "inventory_movement_leg", "inventory_balance_projection", "inventory_lot", "inventory_migration_admission_line"))
                assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_migration_admission")).isEqualTo("1")
            assertThat(scalar("SELECT kind||':'||state FROM inventory_movement")).isEqualTo("OPENING_BALANCE:APPLIED")
            assertThat(scalar("SELECT count(*) FROM inventory_approval_effect")).isEqualTo("1")
            assertThat(scalar("SELECT state FROM inventory_tenant_cutover")).isEqualTo("VALIDATING")
            val event = UUID.fromString(scalar("SELECT id FROM inventory_outbox WHERE event_kind='OPENING_POSTED'"))
            assertThat(inbox.consume(event, "warehouse.approval.receipt") { error("A duplicate delivery cannot apply again") }).isFalse()
        }
        assertThatThrownBy { fixture(token).transaction {
            val event = UUID.fromString(scalar("SELECT id FROM inventory_outbox WHERE event_kind='OPENING_POSTED'"))
            inbox.consume(event, "ordinary.effect") { error("Ordinary consumers remain gated during migration") }
        } }.isInstanceOf(WarehouseContractException::class.java)
        val completed = finalized(token, batch, document)
        assertThat(mapper.readTree(completed.second).path("baselineCount").asInt()).isZero()
        assertThat(mapper.readTree(completed.second).path("baselineTotals").size()).isZero()
        fixture(token).transaction {
            for (table in listOf("inventory_sku", "inventory_document_line", "inventory_movement_leg", "inventory_balance_projection", "inventory_lot"))
                assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo("0")
        }
    }
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @Test fun `approved original asset admission retains obsolete candidate history while changed peer remains reserved`() {
        val batch = setup(4)
        try {
            val balanceFile = ready(batch)
            val document = seal(batch)
            val path = "/api/v1/warehouse/provenance/batches/${batch.id}/finalization"
            val before = mapper.readTree(request("GET", path, batch.token).contentAsString)
            assertThat(before.path("issues").asSequence().map { it.asString() }.toList()).containsExactly("APPROVED_OPENING_REQUIRED")
            val premature = finalizationInput(batch.id, document, review(batch).path("reviewHash").asString())
            assertThat(send(batch.token, path, premature).statusCode()).isEqualTo(409)
            assertThat(runInFailure(batch) { sql("UPDATE inventory_tenant_cutover SET state='ENFORCED',epoch=2,revision=2") }).isEqualTo("42501")
            assertThat(runInFailure(batch) { sql("INSERT INTO inventory_migration_finalization SELECT * FROM inventory_migration_finalization WHERE false") }).isEqualTo("42501")
            val actor = tiers(batch.token, batch.old.location.toString(), 1).single()
            val approval = pending(batch.token, document)
            val response = decide(actor.first, approval)
            assertThat(response.statusCode()).withFailMessage(diagnostic(response)).isEqualTo(200)
            fixture(batch.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_identity_candidate WHERE source_id='${batch.old.second}' AND identity_type='SERIAL' AND raw_value IN ('serial-a','MOVED-AWAY')")).isEqualTo("2")
                assertThat(scalar("SELECT state||':'||admitted_asset_id FROM inventory_identity_claim WHERE identity_type='SERIAL' AND canonical_value='SERIAL-A'")).isEqualTo("ADMITTED:${batch.old.first}")
                assertThat(scalar("SELECT state FROM inventory_identity_claim WHERE identity_type='SERIAL' AND canonical_value='MOVED-AWAY'")).isEqualTo("LEGACY_RESERVED")
                assertThat(scalar("SELECT warehouse_admission||':'||serial_number FROM inventory_serialized_asset WHERE id='${batch.old.second}'")).isEqualTo("LEGACY_UNRESOLVED:MOVED-AWAY")
                assertThat(scalar("SELECT warehouse_admission||':'||serial_number FROM inventory_serialized_asset WHERE id='${batch.old.first}'")).isEqualTo("VERIFIED: Serial-A ")
                assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE warehouse_admission='VERIFIED' AND status='AVAILABLE'")).isEqualTo("2")
            }
            val other = setup(3)
            val objectKey = fixture(batch.token).transaction { scalar("SELECT object_key FROM inventory_migration_evidence WHERE id='$balanceFile'") }
            val original = storage.get(objectKey)
            storage.put(objectKey, original.contentType, original.bytes + byteArrayOf(1))
            try {
                assertThat(send(batch.token, path, premature).statusCode()).isEqualTo(409)
                fixture(batch.token).transaction {
                    assertThat(scalar("SELECT state FROM inventory_tenant_cutover")).isEqualTo("VALIDATING")
                    assertThat(scalar("SELECT count(*) FROM inventory_migration_finalization")).isEqualTo("0")
                }
            } finally { storage.put(objectKey, original.contentType, original.bytes) }
            val completed = finalized(batch.token, batch.id, document)
            val finalizedBody = mapper.readTree(completed.second)
            assertThat(finalizedBody.path("baselineCount").asInt()).isEqualTo(2)
            assertThat(finalizedBody.path("baselineTotals").path("MM").asString()).isEqualTo("82500")
            assertThat(finalizedBody.path("baselineTotals").path("EA").asString()).isEqualTo("1")
            assertThat(finalizedBody.path("cancellationCount").asInt()).isEqualTo(1)
            assertThat(finalizedBody.path("retainedIdentityCount").asInt()).isPositive()
            val payload = finalizationInput(batch.id, document, finalizedBody.path("reviewHash").asString())
            assertThat(runInFailure(batch) { sql("DELETE FROM inventory_migration_finalization") }).isEqualTo("42501")
            val closed = java.util.concurrent.atomic.AtomicBoolean()
            context.addApplicationListener(org.springframework.context.ApplicationListener<org.springframework.context.event.ContextClosedEvent> { closed.set(true) })
            restart = Restart(batch, other, document, completed, payload, area(batch.token), closed)
        } catch (failure: Throwable) { cleanup(batch); throw failure }
    }

    private data class Restart(val batch: Batch, val other: Batch, val document: String, val completed: Pair<String, String>,
        val payload: String, val areaId: String, val stopped: java.util.concurrent.atomic.AtomicBoolean)

    @Order(2)
    @Test fun `stopped application restarts with enforced A validating B original IDs and exact finalization replay`() {
        val saved = requireNotNull(restart)
        assertThat(saved.stopped.get()).describedAs("Original application was fully closed before this boot").isTrue()
        val batch = saved.batch
        val other = saved.other
        val completed = saved.completed
        val payload = saved.payload
        val areaId = saved.areaId
        val freshPort = port
        val path = "/api/v1/warehouse/provenance/batches/${batch.id}/finalization"
        try {
            fun getFresh(token: String, resource: String) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$freshPort$resource"))
                .header("Authorization", "Bearer $token").GET().build(), HttpResponse.BodyHandlers.ofString())
            val replay = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$freshPort$path"))
                .header("Authorization", "Bearer ${batch.token}").header("Idempotency-Key", completed.first)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString())
            assertThat(replay.statusCode()).withFailMessage(replay.body()).isEqualTo(200)
            assertThat(replay.body()).isEqualTo(completed.second)
            val a = getFresh(batch.token, "/api/v1/warehouse/provenance")
            val b = getFresh(other.token, "/api/v1/warehouse/provenance")
            assertThat(a.statusCode()).withFailMessage(a.body()).isEqualTo(200)
            assertThat(b.statusCode()).withFailMessage(b.body()).isEqualTo(200)
            assertThat(mapper.readTree(a.body()).path("cutover").path("state").asString()).isEqualTo("ENFORCED")
            assertThat(mapper.readTree(b.body()).path("cutover").path("state").asString()).isEqualTo("VALIDATING")
            val history = getFresh(batch.token, batch.path(batch.case(batch.old.first)) + "/resolutions")
            assertThat(history.statusCode()).withFailMessage(history.body()).isEqualTo(200)
            val reviewHistory = getFresh(batch.token, "/api/v1/warehouse/provenance/batches/${batch.id}/review")
            assertThat(reviewHistory.statusCode()).withFailMessage(reviewHistory.body()).isEqualTo(200)
            WarehousePostingFixture(context, batch.old.tenant).transaction {
                assertThat(scalar("SELECT serial_number FROM inventory_serialized_asset WHERE id='${batch.old.first}'")).isEqualTo(" Serial-A ")
                assertThat(scalar("SELECT installed_onu_id FROM inventory_serialized_asset WHERE id='${batch.old.installed}'")).isEqualTo(batch.old.episode.toString())
                assertThat(scalar("SELECT customer_id FROM onu WHERE id='${batch.old.episode}'")).isEqualTo(batch.old.customer.toString())
                assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE warehouse_admission='VERIFIED'")).isEqualTo("2")
            }
            WarehousePostingFixture(context, other.old.tenant).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE warehouse_admission='VERIFIED'")).isEqualTo("0")
                assertThat(scalar("SELECT serial_number FROM inventory_serialized_asset WHERE id='${other.old.first}'")).isEqualTo(" Serial-A ")
            }
            val supplier = create("suppliers", batch.token, """{"code":"NEW","name":"New supplier"}""").path("id").asString()
            val source = create("locations", batch.token, """{"code":"RECEIPT_SOURCE","name":"Inbound","kind":"TRANSIT","areaId":"$areaId"}""").path("id").asString()
            val quarantine = create("locations", batch.token, """{"code":"INSPECT","name":"Inspection","kind":"QUARANTINE","areaId":"$areaId"}""").path("id").asString()
            val receipt = Setup(batch.token, supplier, source, quarantine, batch.old.location.toString(), batch.cable, batch.serial)
            val conflicting = draft(receipt, """{"skuId":"${batch.serial}","quantityBase":"1","serials":[{"serial":"MOVED-AWAY"}]}""")
            assertThat(request("POST", "/api/v1/warehouse/receipts/${conflicting.path("id").asString()}/receive", batch.token, """{"expectedRevision":0}""").status).isEqualTo(409)
            assertThat(request("POST", "/api/v1/warehouse/receipts", other.token, draftBody(receipt, """{"skuId":"${batch.serial}","quantityBase":"1","serials":[{"serial":"MOVED-AWAY"}]}""")).status).isEqualTo(409)
            val (disabler, _) = user(batch.token, setOf("iam.user.update"))
            assertThat(request("POST", "/api/users/${batch.actor}/disable", disabler).status).isEqualTo(200)
            val denied = send(batch.token, path, payload, completed.first)
            assertThat(denied.statusCode()).isEqualTo(403)
            assertThat(denied.body()).doesNotContain("finalizedBy", "reviewHash", "baselineTotals")
        } finally { cleanup(batch) }
    }

}
