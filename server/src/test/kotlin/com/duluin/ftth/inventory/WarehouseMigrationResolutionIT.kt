package com.duluin.ftth.inventory

import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReceiptRealStorage::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseMigrationResolutionIT : WarehousePolicyHttpFixture() {
    companion object {
        private val legacy = List(4) { WarehouseMigrationLegacyFixture() }
        private val orphanLocation = UUID.randomUUID()
        private val database = WarehouseSchemaDatabase("172").also { db -> db.ownerFixture { connection ->
            legacy.forEach { it.seed(connection) }
            connection.createStatement().use { sql ->
                val old = legacy[3]
                sql.execute("INSERT INTO inventory_location(id,tenant_id,code,kind) VALUES ('$orphanLocation','${old.otherTenant}','FOREIGN-SECRET','WAREHOUSE')")
                sql.execute("UPDATE inventory_balance_projection SET location_id='$orphanLocation' WHERE id='${old.balance}'")
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
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private data class Batch(val token: String, val actor: String, val old: WarehouseMigrationLegacyFixture, val id: String,
        val cases: List<JsonNode>, val cable: String, val serial: String) {
        fun case(sourceId: UUID) = cases.single { it.path("sourceId").asString() == sourceId.toString() }
        fun path(source: JsonNode) = "/api/v1/warehouse/provenance/batches/$id/cases/${source.path("id").asString()}"
    }

    private fun setup(index: Int, platform: Boolean = false): Batch {
        val old = legacy[index]
        val token = tenant("migration-${old.tenant}")
        val actor = mapper.readTree(request("GET", "/api/me", token).contentAsString).path("id").asString()
        database.ownerFixture { connection -> connection.createStatement().use { sql ->
            sql.execute("SET app.tenant_id='${old.tenant}'")
            sql.execute("UPDATE inventory_location SET name='Gudang lama',area_id='${area(token)}',revision=revision+1 WHERE id='${old.location}'")
            sql.execute("UPDATE customer SET area_id='${area(token)}' WHERE id='${old.customer}'")
            if (platform) sql.execute("UPDATE app_user SET platform_admin=true WHERE id='$actor'")
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

    private fun evidence(batch: Batch, source: JsonNode): String {
        val boundary = "migration-resolution-file"
        val json = mapper.writeValueAsString(mapOf("expectedEpoch" to 1, "expectedCaseHash" to source.path("sourceHash").asString(), "label" to "Bukti pemeriksaan fisik"))
        val bytes = ("--$boundary\r\nContent-Disposition: form-data; name=\"request\"\r\n\r\n$json\r\n--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"proof.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").toByteArray() +
            ReceiptEvidenceFixtures.pdf() + "\r\n--$boundary--\r\n".toByteArray()
        val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port${batch.path(source)}/evidence"))
            .header("Authorization", "Bearer ${batch.token}").header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Content-Type", "multipart/form-data; boundary=$boundary").timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201)
        return mapper.readTree(response.body()).path("id").asString()
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

    @Test fun `unit proposals derive exact source amounts and keep revision history without posting stock`() {
        val batch = setup(0)
        try {
            val source = batch.case(batch.old.balance)
            val file = evidence(batch, source)
            val path = batch.path(source) + "/resolutions"
            val input = body(source, file, "BASELINE_STOCK", stock = stock(batch.cable, "M"))
            val key = UUID.randomUUID().toString()
            val original = send(batch.token, path, input, key)
            assertThat(original.statusCode()).withFailMessage(original.body()).isEqualTo(201)
            assertThat(mapper.readTree(original.body()).path("stock").path("quantityBase").asString()).isEqualTo("82500000")
            val corrected = send(batch.token, path, body(source, file, "BASELINE_STOCK", 1, stock(batch.cable, "MM")))
            assertThat(corrected.statusCode()).withFailMessage(corrected.body()).isEqualTo(201)
            assertThat(mapper.readTree(corrected.body()).path("stock").path("quantityBase").asString()).isEqualTo("82500")
            assertThat(send(batch.token, path, input, key).body()).isEqualTo(original.body())
            assertThat(send(batch.token, path, input).statusCode()).isEqualTo(409)
            assertThat(send(batch.token, path, body(source, file), key).statusCode()).isEqualTo(409)
            assertThat(send(batch.token, path, input.replace("\"skuId\":", "\"quantityBase\":\"1\",\"skuId\":")).statusCode()).isEqualTo(400)
            assertThat(send(batch.token, path, body(source, file, revision = 2).replace("[\"$file\"]", "[]")).statusCode()).isEqualTo(400)
            val history = request("GET", "$path?size=1", batch.token)
            assertThat(history.status).isEqualTo(200)
            assertThat(mapper.readTree(history.contentAsString).path("totalElements").asInt()).isEqualTo(2)
            assertThat(mapper.readTree(history.contentAsString).path("items")[0].path("revision").asInt()).isEqualTo(2)
            assertThat(request("GET", "$path?size=101", batch.token).status).isEqualTo(400)
            for (bad in listOf(stock(batch.cable, "EA"), stock(batch.serial, "EA"), stock(batch.cable, "MM", "UNKNOWN"), stock(batch.cable, "MM", "CUSTOMER")))
                assertThat(send(batch.token, path, body(source, file, "BASELINE_STOCK", 2, bad)).statusCode()).isEqualTo(409)
            assertThat(send(batch.token, path, body(source, UUID.randomUUID().toString(), revision = 2)).statusCode()).isEqualTo(404)
            val otherCase = batch.case(batch.old.first)
            assertThat(send(batch.token, batch.path(otherCase) + "/resolutions", body(otherCase, file)).statusCode()).isEqualTo(404)
            val otherActor = user(batch.token, setOf("inventory.provenance.manage"))
            grant(batch.token, otherActor.second, listOf(batch.old.location.toString()))
            assertThat(send(otherActor.first, path, input, key).statusCode()).isEqualTo(403)
            assertThat(request("GET", path, tenant()).status).isEqualTo(404)
            fixture(batch.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_migration_resolution")).isEqualTo("2")
                assertThat(scalar("SELECT count(*) FROM inventory_document")).isEqualTo("0")
                assertThat(scalar("SELECT quantity FROM inventory_balance_projection WHERE id='${batch.old.balance}'")).isEqualTo("82500")
                assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE warehouse_admission='VERIFIED'")).isEqualTo("0")
            }
            assertThatThrownBy { fixture(batch.token).transaction { sql("UPDATE inventory_migration_resolution SET reason='rewrite'") } }.hasRootCauseInstanceOf(java.sql.SQLException::class.java)
            assertThatThrownBy { fixture(batch.token).transaction { sql("DELETE FROM inventory_migration_resolution") } }.hasRootCauseInstanceOf(java.sql.SQLException::class.java)
            val forged = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("batchId" to batch.id,
                "caseId" to source.path("id").asString(), "input" to mapper.readTree(body(source, file, "BASELINE_STOCK", 2, stock(batch.cable, "MM"))))))
            assertThatThrownBy { fixture(batch.token).transaction { jdbc { connection ->
                connection.prepareStatement("""INSERT INTO inventory_migration_resolution
                    SELECT expanded.* FROM inventory_migration_resolution original
                    CROSS JOIN LATERAL jsonb_populate_record(NULL::inventory_migration_resolution, to_jsonb(original) ||
                        jsonb_build_object('id',?::uuid,'revision',3,'operation_key',?::text,'canonical_payload',?::text,'payload_hash',?::text,
                            'stock',jsonb_set(original.stock,'{quantityBase}','"999999999"'::jsonb))) expanded
                    WHERE original.tenant_id=? AND original.case_id=? AND original.revision=2""").use { statement ->
                    statement.setObject(1, UUID.randomUUID()); statement.setString(2, UUID.randomUUID().toString())
                    statement.setString(3, forged.json); statement.setString(4, forged.hash)
                    statement.setObject(5, batch.old.tenant); statement.setObject(6, UUID.fromString(source.path("id").asString()))
                    statement.executeUpdate()
                }
            } } }.hasStackTraceContaining("resolution stock must be derived from the preserved source")
            storage.delete("${batch.old.tenant}/warehouse/migrations/${batch.id}/${source.path("id").asString()}/$file")
            assertThat(send(batch.token, path, input, key).statusCode()).isEqualTo(409)
            assertThat(send(batch.token, path, body(source, file, revision = 2)).statusCode()).isEqualTo(409)
        } finally { cleanup(batch) }
    }

    @Test fun `duplicate and historical proposals preserve actual IDs and pending effects require explicit cancellation review`() {
        val batch = setup(1)
        try {
            val first = batch.case(batch.old.first)
            val second = batch.case(batch.old.second)
            val firstFile = evidence(batch, first)
            val firstResult = send(batch.token, batch.path(first) + "/resolutions", body(first, firstFile, "BASELINE_STOCK", stock = stock(batch.serial, "EA")))
            assertThat(firstResult.statusCode()).withFailMessage(firstResult.body()).isEqualTo(201)
            val secondFile = evidence(batch, second)
            val duplicate = send(batch.token, batch.path(second) + "/resolutions", body(second, secondFile, "DUPLICATE", duplicate = first.path("id").asString()))
            assertThat(duplicate.statusCode()).withFailMessage(duplicate.body()).isEqualTo(201)
            assertThat(mapper.readTree(duplicate.body()).path("duplicateResolutionId")).isEqualTo(mapper.readTree(firstResult.body()).path("id"))
            val installed = batch.case(batch.old.installed)
            val installedFile = evidence(batch, installed)
            assertThat(send(batch.token, batch.path(installed) + "/resolutions", body(installed, installedFile, "BASELINE_STOCK", stock = stock(batch.serial, "EA"))).statusCode()).isEqualTo(409)
            val pending = batch.case(batch.old.pending)
            val pendingFile = evidence(batch, pending)
            assertThat(send(batch.token, batch.path(pending) + "/resolutions", body(pending, pendingFile)).statusCode()).isEqualTo(409)
            val cancelled = send(batch.token, batch.path(pending) + "/resolutions", body(pending, pendingFile, "CANCEL_PENDING"))
            assertThat(cancelled.statusCode()).withFailMessage(cancelled.body()).isEqualTo(201)
            val episode = batch.case(batch.old.unmatchedEpisode)
            val episodeFile = evidence(batch, episode)
            assertThat(send(batch.token, batch.path(episode) + "/resolutions", body(episode, episodeFile, "CANCEL_PENDING")).statusCode()).isEqualTo(409)
            assertThat(send(batch.token, batch.path(episode) + "/resolutions", body(episode, episodeFile)).statusCode()).isEqualTo(201)
            fixture(batch.token).transaction {
                assertThat(scalar("SELECT serial_number FROM inventory_serialized_asset WHERE id='${batch.old.first}'")).isEqualTo(" Serial-A ")
                assertThat(scalar("SELECT serial_number FROM inventory_serialized_asset WHERE id='${batch.old.second}'")).isEqualTo("serial-a")
                assertThat(scalar("SELECT state FROM inventory_movement WHERE id='${batch.old.pending}'")).isEqualTo("PENDING_APPROVAL")
                assertThat(scalar("SELECT count(*) FROM inventory_serialized_asset WHERE warehouse_admission='VERIFIED'")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_identity_claim WHERE state='ADMITTED'")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_document")).isEqualTo("0")
            }
        } finally { cleanup(batch) }
    }

    @Test fun `competing revisions serialize and revoked current authority rejects an old token and original replay`() {
        val batch = setup(2)
        try {
            val source = batch.case(batch.old.balance)
            val file = evidence(batch, source)
            val path = batch.path(source) + "/resolutions"
            val operator = user(batch.token, setOf("inventory.provenance.manage"))
            grant(batch.token, operator.second, listOf(batch.old.location.toString()))
            val inputs = listOf(body(source, file, reason = "First review"), body(source, file, reason = "Second review"))
            val keys = List(2) { UUID.randomUUID().toString() }
            val outcomes = inputs.mapIndexed { index, body -> client.sendAsync(command(operator.first, path, body, keys[index]), HttpResponse.BodyHandlers.ofString()) }
                .map { it.get(25, TimeUnit.SECONDS) }
            assertThat(outcomes.map { it.statusCode() }.sorted()).isEqualTo(listOf(201, 409))
            val winner = outcomes.indexOfFirst { it.statusCode() == 201 }
            assertThat(send(operator.first, path, inputs[winner], keys[winner]).body()).isEqualTo(outcomes[winner].body())
            val principal = mapper.readTree(request("GET", "/api/users/${operator.second}", batch.token).contentAsString)
            assertThat(request("PUT", "/api/users/${operator.second}/access", batch.token, mapper.writeValueAsString(mapOf(
                "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to emptyList<String>()))).status).isEqualTo(200)
            assertThat(send(operator.first, path, inputs[winner], keys[winner]).statusCode()).isEqualTo(404)
            assertThat(request("GET", path, operator.first).status).isEqualTo(404)
            fixture(batch.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_migration_resolution")).isEqualTo("1") }
        } finally { cleanup(batch) }
    }

    @Test fun `platform can review an orphan preserved reference without leaking or admitting another tenants location`() {
        val batch = setup(3, platform = true)
        try {
            val source = batch.case(batch.old.balance)
            assertThat(source.path("location").isNull).isTrue()
            assertThat(source.toString()).doesNotContain("FOREIGN-SECRET")
            val file = evidence(batch, source)
            val path = batch.path(source) + "/resolutions"
            val denied = send(batch.token, path, body(source, file, "BASELINE_STOCK", stock = stock(batch.cable, "MM")))
            assertThat(denied.statusCode()).isEqualTo(409)
            assertThat(send(batch.token, path, body(source, file)).statusCode()).isEqualTo(201)
            val operator = user(batch.token, setOf("inventory.provenance.manage"))
            grant(batch.token, operator.second, listOf(batch.old.location.toString()))
            assertThat(request("GET", "/api/v1/warehouse/provenance", operator.first).status).isEqualTo(404)
            assertThat(request("GET", path, operator.first).status).isEqualTo(404)
        } finally { cleanup(batch) }
    }
}
