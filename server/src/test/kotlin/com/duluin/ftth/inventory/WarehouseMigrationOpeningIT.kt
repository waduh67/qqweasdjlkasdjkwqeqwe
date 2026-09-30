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
class WarehouseMigrationOpeningIT : WarehousePolicyHttpFixture() {
    companion object {
        private val legacy = List(4) { WarehouseMigrationLegacyFixture() }
        private val database = WarehouseSchemaDatabase("172").also { db -> db.ownerFixture { connection ->
            legacy.forEach { it.seed(connection) }
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

    private fun setup(index: Int): Batch {
        val old = legacy[index]
        val token = tenant("migration-${old.tenant}")
        val actor = mapper.readTree(request("GET", "/api/me", token).contentAsString).path("id").asString()
        database.ownerFixture { connection -> connection.createStatement().use { sql ->
            sql.execute("SET app.tenant_id='${old.tenant}'")
            sql.execute("UPDATE inventory_location SET name='Gudang lama',area_id='${area(token)}',revision=revision+1 WHERE id='${old.location}'")
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
        resolve(batch, batch.old.second, "DUPLICATE", duplicate = batch.case(batch.old.first).path("id").asString())
        val balanceFile = resolve(batch, batch.old.balance, "BASELINE_STOCK", stock(batch.cable, "MM"))
        resolve(batch, batch.old.pending, "CANCEL_PENDING")
        assertThat(review(batch).path("issues").size()).isZero()
        return balanceFile
    }

    @Test fun `full batch review excludes unresolved history and seals exact stock without valuation or admission`() {
        val batch = setup(0)
        try {
            val initial = review(batch)
            assertThat(initial.path("manifest").path("cases").size()).isEqualTo(11)
            assertThat(initial.path("issues").size()).isEqualTo(4)
            assertThat(initial.path("manifest").path("cases").asSequence().filter { !it.path("resolutionRequired").asBoolean() }
                .all { it.path("resolution").isNull }).isTrue()
            val path = "/api/v1/warehouse/provenance/batches/${batch.id}/opening"
            assertThat(send(batch.token, path, openingInput(batch, initial)).statusCode()).isEqualTo(409)
            resolve(batch, batch.old.first, "BASELINE_STOCK", stock(batch.serial, "EA"))
            assertThat(review(batch).path("issues").asSequence().map { it.path("code").asString() }.toList())
                .contains("IDENTITY_CONFLICT_REQUIRES_REVIEW")
            resolve(batch, batch.old.second, "DUPLICATE", duplicate = batch.case(batch.old.first).path("id").asString())
            resolve(batch, batch.old.balance, "BASELINE_STOCK", stock(batch.cable, "MM"))
            resolve(batch, batch.old.pending, "CANCEL_PENDING")
            val reviewed = review(batch)
            assertThat(reviewed.path("issues").size()).isZero()
            assertThat(send(batch.token, path, openingInput(batch, initial)).statusCode()).isEqualTo(409)
            val input = openingInput(batch, reviewed)
            assertThat(send(batch.token, path, input.replace("\"expectedEpoch\":", "\"quantityBase\":\"999\",\"expectedEpoch\":")).statusCode()).isEqualTo(400)
            val key = UUID.randomUUID().toString()
            val commands = List(2) { client.sendAsync(command(batch.token, path, input, key), HttpResponse.BodyHandlers.ofString()) }
                .map { it.get(25, TimeUnit.SECONDS) }
            assertThat(commands.map { it.statusCode() }).isEqualTo(listOf(201, 201))
            assertThat(commands[0].body()).isEqualTo(commands[1].body())
            val original = commands[0].body()
            val opened = mapper.readTree(original)
            assertThat(opened.path("manifest")).isEqualTo(reviewed.path("manifest"))
            val id = opened.path("id").asString()
            val current = request("GET", "$path/$id", batch.token)
            assertThat(current.status).withFailMessage(current.contentAsString).isEqualTo(200)
            val expectedCurrent = mapper.readTree(original) as tools.jackson.databind.node.ObjectNode
            expectedCurrent.put("state", "DRAFT")
            assertThat(mapper.readTree(current.contentAsString)).isEqualTo(expectedCurrent)
            fixture(batch.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_migration_opening_request")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_document WHERE kind='OPENING_BALANCE' AND state='DRAFT'")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_document_line WHERE quantity_base IN (1,82500) AND cost_total_minor IS NULL AND stock_identity_id IS NULL")).isEqualTo("2")
                assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("2")
                assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE warehouse_admission='VERIFIED'")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_identity_claim WHERE state='ADMITTED'")).isEqualTo("0")
                assertThat(scalar("SELECT state FROM inventory_tenant_cutover")).isEqualTo("VALIDATING")
                assertThat(scalar("SELECT state FROM inventory_movement WHERE id='${batch.old.pending}'")).isEqualTo("PENDING_APPROVAL")
            }
            for (sql in listOf("UPDATE inventory_document SET reason='rewrite' WHERE id='$id'",
                "DELETE FROM inventory_document WHERE id='$id'", "UPDATE inventory_document_line SET quantity_base=999 WHERE document_id='$id'",
                "DELETE FROM inventory_document_line WHERE document_id='$id'", "UPDATE inventory_migration_opening_request SET review_hash=repeat('0',64)",
                "DELETE FROM inventory_migration_opening_request")) {
                assertThatThrownBy { fixture(batch.token).transaction { sql(sql) } }.hasRootCauseInstanceOf(java.sql.SQLException::class.java)
            }
            val forgedId = UUID.randomUUID()
            assertThatThrownBy { fixture(batch.token).transaction {
                sql("""INSERT INTO inventory_document SELECT forged.* FROM inventory_document original
                    CROSS JOIN LATERAL jsonb_populate_record(NULL::inventory_document,to_jsonb(original)||
                        jsonb_build_object('id','$forgedId','code','OPEN-$forgedId')) forged WHERE original.id='$id'""")
                sql("""INSERT INTO inventory_document_line SELECT forged.* FROM inventory_document_line original
                    CROSS JOIN LATERAL jsonb_populate_record(NULL::inventory_document_line,to_jsonb(original)||
                        jsonb_build_object('id',gen_random_uuid(),'document_id','$forgedId','quantity_base',999)) forged
                    WHERE original.document_id='$id'""")
                sql("""INSERT INTO inventory_migration_opening_request SELECT forged.* FROM inventory_migration_opening_request original
                    CROSS JOIN LATERAL jsonb_populate_record(NULL::inventory_migration_opening_request,to_jsonb(original)||
                        jsonb_build_object('id','$forgedId','operation_key','$forgedId','original_body',
                            (original.original_body::jsonb||jsonb_build_object('id','$forgedId','code','OPEN-$forgedId'))::text)) forged
                    WHERE original.id='$id'""")
            } }.hasStackTraceContaining("opening lines must exactly preserve resolved stock without invented origin or valuation")
            fixture(batch.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_document")).isEqualTo("1") }
            // Later proposals cannot rewrite the approved-review candidate or its original receipt.
            resolve(batch, batch.old.balance, "BASELINE_STOCK", stock(batch.cable, "M"), revision = 1)
            assertThat(review(batch).path("reviewHash")).isNotEqualTo(reviewed.path("reviewHash"))
            assertThat(send(batch.token, path, input, key).body()).isEqualTo(original)
            assertThat(send(batch.token, path, input).statusCode()).isEqualTo(409)
            val operator = user(batch.token, setOf("inventory.provenance.manage"))
            grant(batch.token, operator.second, listOf(batch.old.location.toString()))
            assertThat(send(operator.first, path, input, key).statusCode()).isEqualTo(403)
            assertThat(request("GET", "$path/$id", tenant()).status).isEqualTo(404)
            val principal = mapper.readTree(request("GET", "/api/users/${operator.second}", batch.token).contentAsString)
            assertThat(request("PUT", "/api/users/${operator.second}/access", batch.token, mapper.writeValueAsString(mapOf(
                "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to emptyList<String>()))).status).isEqualTo(200)
            assertThat(request("GET", "$path/$id", operator.first).status).isEqualTo(404)
            val sealedCase = opened.path("manifest").path("cases").asSequence().first { it.path("resolution").path("kind").asString() == "BASELINE_STOCK" }
            val sealedFile = sealedCase.path("resolution").path("evidence")[0].path("id").asString()
            storage.delete("${batch.old.tenant}/warehouse/migrations/${batch.id}/${sealedCase.path("caseId").asString()}/$sealedFile")
            assertThat(send(batch.token, path, input, key).statusCode()).isEqualTo(409)
        } finally { cleanup(batch) }
    }

    @Test fun `due opening preserves its sealed review and cannot admit legacy stock`() {
        val batch = setup(3)
        try {
            ready(batch)
            val input = openingInput(batch)
            val database = fixture(batch.token)
            val clock = WarehouseDraftClockFixture(database)
            clock.policy(1)
            val path = "/api/v1/warehouse/provenance/batches/${batch.id}/opening"
            val original = send(batch.token, path, input, "expiry-opening")
            assertThat(original.statusCode()).withFailMessage(original.body()).isEqualTo(201)
            val id = mapper.readTree(original.body()).path("id").asString()
            val before = WarehouseDraftExpiryFacts.capture(database, id)
            clock.awaitDocument(id)
            val detail = request("GET", "$path/$id", batch.token)
            assertThat(detail.status).withFailMessage(detail.contentAsString).isEqualTo(200)
            val current = mapper.readTree(detail.contentAsString)
            assertThat(current.path("state").asString()).isEqualTo("EXPIRED")
            assertThat(current.path("manifest")).isEqualTo(mapper.readTree(original.body()).path("manifest"))
            val list = request("GET", path, batch.token)
            assertThat(list.status).withFailMessage(list.contentAsString).isEqualTo(200)
            assertThat(mapper.readTree(list.contentAsString).path("items").single().path("state").asString()).isEqualTo("EXPIRED")
            WarehouseDraftExpiryFacts.rejected(request("POST", "/api/v1/warehouse/approvals/request", batch.token,
                """{"sourceDocumentId":"$id","sourceRevision":0}"""))
            assertThat(send(batch.token, path, input, "expiry-opening").body()).isEqualTo(original.body())
            WarehouseDraftExpiryFacts.expire(database, id)
            assertThat(WarehouseDraftExpiryFacts.capture(database, id)).isEqualTo(before)
            assertThat(send(batch.token, path, input, "expiry-fresh").statusCode()).isEqualTo(201)
        } finally { cleanup(batch) }
    }

    @Test fun `changed winners masters and actual evidence prevent sealing stale or incomplete proposals`() {
        val batch = setup(1)
        try {
            val file = ready(batch)
            val path = "/api/v1/warehouse/provenance/batches/${batch.id}/opening"
            resolve(batch, batch.old.first, "BASELINE_STOCK", stock(batch.serial, "EA"), revision = 1)
            assertThat(review(batch).path("issues").asSequence().map { it.path("code").asString() }.toList())
                .contains("DUPLICATE_WINNER_CHANGED", "IDENTITY_CONFLICT_REQUIRES_REVIEW")
            assertThat(send(batch.token, path, openingInput(batch)).statusCode()).isEqualTo(409)
            resolve(batch, batch.old.second, "DUPLICATE", duplicate = batch.case(batch.old.first).path("id").asString(), revision = 1)
            val revision = mapper.readTree(request("GET", "/api/v1/warehouse/skus/${batch.cable}", batch.token).contentAsString).path("revision").asLong()
            val changed = request("PUT", "/api/v1/warehouse/skus/${batch.cable}", batch.token,
                """{"expectedRevision":$revision,"code":"CABLE","name":"Kabel diperiksa ulang","tracking":"LOT","baseUnit":"MM"}""", UUID.randomUUID().toString())
            assertThat(changed.status).withFailMessage(changed.contentAsString).isEqualTo(200)
            assertThat(review(batch).path("issues").asSequence().map { it.path("code").asString() }.toList()).contains("STOCK_REVISION_CHANGED")
            assertThat(send(batch.token, path, openingInput(batch)).statusCode()).isEqualTo(409)
            resolve(batch, batch.old.balance, "BASELINE_STOCK", stock(batch.cable, "MM"), revision = 1)
            assertThat(review(batch).path("issues").size()).isZero()
            val currentCase = batch.case(batch.old.balance)
            val history = mapper.readTree(request("GET", batch.path(currentCase) + "/resolutions", batch.token).contentAsString)
            val currentFile = history.path("items")[0].path("evidence")[0].path("id").asString()
            assertThat(currentFile).isNotEqualTo(file)
            storage.delete("${batch.old.tenant}/warehouse/migrations/${batch.id}/${currentCase.path("id").asString()}/$currentFile")
            assertThat(send(batch.token, path, openingInput(batch)).statusCode()).isEqualTo(409)
            fixture(batch.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_document")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_migration_opening_request")).isEqualTo("0")
            }
        } finally { cleanup(batch) }
    }

    @Test fun `existing empty tenant produces an explicit zero baseline control without fake physical lines or receipt`() {
        val old = legacy[2]
        val token = tenant("migration-${old.otherTenant}")
        val place = create("locations", token,
            """{"code":"EMPTY-REVIEW","name":"Gudang tanpa stok","kind":"WAREHOUSE","areaId":"${area(token)}"}""").path("id").asString()
        val report = mapper.readTree(request("GET", "/api/v1/warehouse/provenance", token).contentAsString)
        val begin = send(token, "/api/v1/warehouse/provenance/batches", mapper.writeValueAsString(mapOf(
            "expectedEpoch" to 0, "expectedPreservationHash" to report.path("preservationHash").asString())))
        assertThat(begin.statusCode()).withFailMessage(begin.body()).isEqualTo(201)
        val batch = mapper.readTree(begin.body()).path("batch").path("id").asString()
        val reviewResponse = request("GET", "/api/v1/warehouse/provenance/batches/$batch/review", token)
        assertThat(reviewResponse.status).withFailMessage(reviewResponse.contentAsString).isEqualTo(200)
        val review = mapper.readTree(reviewResponse.contentAsString)
        assertThat(review.path("manifest").path("cases").size()).isZero()
        assertThat(review.path("issues").size()).isZero()
        val placeRevision = mapper.readTree(request("GET", "/api/v1/warehouse/locations/$place", token).contentAsString).path("revision").asLong()
        val input = mapper.writeValueAsString(mapOf("expectedEpoch" to 1, "expectedReviewHash" to review.path("reviewHash").asString(),
            "reviewLocationId" to place, "expectedReviewLocationRevision" to placeRevision,
            "migrationReference" to "Pemeriksaan tenant tanpa stok", "reason" to "Tidak ditemukan stok fisik atau efek lama"))
        val opened = send(token, "/api/v1/warehouse/provenance/batches/$batch/opening", input)
        assertThat(opened.statusCode()).withFailMessage(opened.body()).isEqualTo(201)
        val approvers = List(2) { approver(token, listOf(place)) }
        configure(token, openingPolicy(place, approvers.map { it.second }))
        val evaluation = evaluate(token, mapper.readTree(opened.body()).path("id").asString())
        assertThat(evaluation.status).withFailMessage(evaluation.contentAsString).isEqualTo(200)
        val policy = mapper.readTree(evaluation.contentAsString)
        assertThat(policy.path("tiers").size()).isEqualTo(2)
        for (field in listOf("valueNumerator", "valueDenominator", "currency")) assertThat(policy.path(field).isMissingNode).isTrue()
        fixture(token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_document WHERE kind='OPENING_BALANCE'")).isEqualTo("1")
            for (table in listOf("inventory_document_line", "inventory_sku", "inventory_movement", "inventory_migration_evidence", "inventory_balance_projection"))
                assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo("0")
            assertThat(scalar("SELECT state FROM inventory_tenant_cutover")).isEqualTo("VALIDATING")
        }
    }

    private fun openingPolicy(location: String, users: List<String>, participants: List<String> = emptyList()) = mapper.writeValueAsString(mapOf(
        "expectedRevision" to 0, "currency" to "IDR", "expiryHours" to 24, "warehouseIds" to listOf(location),
        "rules" to listOf(mapOf("operation" to "OPENING_BALANCE", "tiers" to users.mapIndexed { index, actor ->
            mapOf("minimumMinor" to ((index + 1L) * 100000000).toString(), "userIds" to (participants + actor), "roleIds" to emptyList<String>())
        }))))

    @Test fun `unvalued opening evaluates every tier excludes all review participants and requires current customer areas`() {
        val batch = setup(2)
        try {
            val permissions = setOf("inventory.provenance.manage", "inventory.approval.view", "inventory.approval.decide", "inventory.cost.view")
            val reviewer = user(batch.token, permissions)
            val requester = user(batch.token, permissions)
            val uploader = user(batch.token, permissions)
            for (actor in listOf(reviewer, requester, uploader)) grant(batch.token, actor.second, listOf(batch.old.location.toString()))
            val first = batch.case(batch.old.first)
            val file = evidence(batch.copy(token = uploader.first, actor = uploader.second), first)
            val resolution = send(reviewer.first, batch.path(first) + "/resolutions", body(first, file, "BASELINE_STOCK", stock = stock(batch.serial, "EA")))
            assertThat(resolution.statusCode()).withFailMessage(resolution.body()).isEqualTo(201)
            val reviewBatch = batch.copy(token = reviewer.first, actor = reviewer.second)
            resolve(reviewBatch, batch.old.second, "DUPLICATE", duplicate = first.path("id").asString())
            resolve(reviewBatch, batch.old.balance, "BASELINE_STOCK", stock(batch.cable, "MM"))
            resolve(reviewBatch, batch.old.pending, "CANCEL_PENDING")
            val opened = send(requester.first, "/api/v1/warehouse/provenance/batches/${batch.id}/opening", openingInput(batch))
            assertThat(opened.statusCode()).withFailMessage(opened.body()).isEqualTo(201)
            val id = mapper.readTree(opened.body()).path("id").asString()
            assertThat(evaluate(requester.first, id).status).isEqualTo(409)
            val approvers = List(3) { approver(batch.token, listOf(batch.old.location.toString())) }
            configure(batch.token, openingPolicy(batch.old.location.toString(), approvers.map { it.second },
                listOf(batch.actor, reviewer.second, requester.second, uploader.second)))
            val evaluated = evaluate(requester.first, id)
            assertThat(evaluated.status).withFailMessage(evaluated.contentAsString).isEqualTo(200)
            val policy = mapper.readTree(evaluated.contentAsString)
            assertThat(policy.path("policy").path("operation").asString()).isEqualTo("OPENING_BALANCE")
            assertThat(policy.path("message").asString()).contains("unknown", "Every configured approval tier")
            assertThat(policy.path("tiers").size()).isEqualTo(3)
            policy.path("tiers").asSequence().forEachIndexed { index, tier ->
                assertThat(tier.path("approvers").asSequence().map { it.path("userId").asString() }.toList()).isEqualTo(listOf(approvers[index].second))
            }
            for (field in listOf("valueNumerator", "valueDenominator", "currency")) assertThat(policy.path(field).isMissingNode).isTrue()
            assertThat(evaluate(approvers.first().first, id).status).isEqualTo(200)
            val areaResponse = request("POST", "/api/areas", batch.token, """{"code":"CUSTOMER-REVIEW","name":"Area pelanggan saat ini"}""")
            assertThat(areaResponse.status).isEqualTo(201)
            val customerArea = mapper.readTree(areaResponse.contentAsString).path("id").asString()
            database.ownerFixture { connection -> connection.createStatement().use { sql ->
                sql.execute("SET app.tenant_id='${batch.old.tenant}'")
                sql.execute("UPDATE customer SET area_id='$customerArea' WHERE id='${batch.old.customer}'")
            } }
            assertThat(evaluate(batch.token, id).status).isEqualTo(404)
            fun areas(actor: String) {
                val principal = mapper.readTree(request("GET", "/api/users/$actor", batch.token).contentAsString)
                assertThat(request("PUT", "/api/users/$actor/access", batch.token, mapper.writeValueAsString(mapOf(
                    "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(),
                    "areaIds" to listOf(area(batch.token), customerArea)))).status).isEqualTo(200)
            }
            areas(batch.actor)
            val missingArea = evaluate(batch.token, id)
            assertThat(missingArea.status).withFailMessage(missingArea.contentAsString).isEqualTo(409)
            assertThat(mapper.readTree(missingArea.contentAsString).path("code").asString()).isEqualTo("INDEPENDENT_APPROVER_REQUIRED")
            approvers.forEach { areas(it.second) }
            assertThat(evaluate(batch.token, id).status).isEqualTo(200)
            assertThat(evaluate(approvers.first().first, id).status).isEqualTo(200)
            resolve(batch, batch.old.balance, "BASELINE_STOCK", stock(batch.cable, "M"), revision = 1)
            val stale = evaluate(batch.token, id)
            assertThat(stale.status).isEqualTo(409)
            assertThat(mapper.readTree(stale.contentAsString).path("code").asString()).isEqualTo("STALE_REVISION")
            fixture(batch.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_approval")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE warehouse_admission='VERIFIED'")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_document_line WHERE cost_total_minor IS NOT NULL")).isEqualTo("0")
            }
        } finally { cleanup(batch) }
    }
}
