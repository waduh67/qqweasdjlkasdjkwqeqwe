package com.duluin.ftth.inventory

import com.duluin.ftth.customer.CustomerAssetReplacementFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import java.util.Base64
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart

class WarehouseDraftExpiryAssetIT : CustomerAssetReplacementFixture() {
    override fun stockReceiptCost() = mapOf("totalMinor" to "200000", "currency" to "IDR")

    @ParameterizedTest @ValueSource(strings = ["ASSET_LOSS", "TITLE_CORRECTION"])
    fun `due asset exception preserves accepted assignment and title history`(kind: String) {
        val mode = if (kind == "ASSET_LOSS") "LOAN" else "SALE"
        val old = ownershipCase(mode)
        val receipt = old.installation.receipt
        val admin = receipt.stock.token
        val asset = receipt.input.lines.single().stockIdentityId
        assertThat(accept(old).status).isEqualTo(200)
        val handover = fixture(admin).transaction {
            scalar("SELECT id FROM inventory_asset_handover WHERE assignment_id='${old.installation.operation}'")
        }
        val source = fixture(admin).transaction { scalar("SELECT location_id FROM inventory_serialized_asset WHERE id='$asset'") }
        val sink = create("locations", admin, """{"code":"LOAN_LOSS","name":"Approved lost loans","kind":"LOST"}""").path("id").asString()
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jK1cAAAAASUVORK5CYII=")
        val signed = mvc.perform(multipart(HttpMethod.PUT, "/api/work-orders/${receipt.workOrder}/signature")
            .file(MockMultipartFile("file", "loss-assessment.png", "image/png", png))
            .param("signerName", "Loan loss assessment")
            .param("correctionReason", "Record a later witnessed loss assessment and preserve the original acceptance")
            .header("Authorization", "Bearer ${receipt.receiver.first}")).andReturn().response
        assertThat(signed.status).withFailMessage(signed.contentAsString).isEqualTo(200)
        val evidence = UUID.fromString(mapper.readTree(signed.contentAsString).path("revisionId").asString())
        val input = WarehouseAssetLossInput(old.installation.operation, UUID.fromString(handover), 1,
            if (mode == "SALE") 1 else 0, fixture(admin).transaction { scalar("SELECT warehouse_revision FROM work_order WHERE id='${receipt.workOrder}'").toLong() },
            UUID.fromString(sink), "Confirmed unrecovered device loss with independent assessment", evidence)
        val body = mapper.writeValueAsString(input)

        val path = if (kind == "ASSET_LOSS") "/api/v1/warehouse/asset-losses" else "/api/v1/warehouse/asset-title-corrections"
        val payload = if (kind == "ASSET_LOSS") body else """{"assignmentId":"${old.installation.operation}",
            "sourceHandoverId":"$handover","expectedAssignmentRevision":1,"expectedTitleRevision":1,
            "targetOwner":"ISP","reason":"Signed title correction","evidenceId":"$evidence"}"""
        val database = fixture(admin)
        val clock = WarehouseDraftClockFixture(database)
        clock.policy(1)
        val original = request("POST", path, admin, payload, "expiry-asset")
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(201)
        val id = mapper.readTree(original.contentAsString).path(if (kind=="ASSET_LOSS") "id" else "documentId").asString()
        val before = WarehouseDraftExpiryFacts.capture(database, id)
        clock.awaitDocument(id)
        val sourceView = request("GET", "/api/v1/warehouse/approvals/sources/$id", admin)
        assertThat(sourceView.status).withFailMessage(sourceView.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(sourceView.contentAsString).path("document").path("state").asString()).isEqualTo("EXPIRED")
        assertThat(mapper.readTree(sourceView.contentAsString).path("canRequest").asBoolean()).isFalse()
        if (kind == "ASSET_LOSS") {
            val detail = request("GET", "$path/$id", admin)
            assertThat(detail.status).withFailMessage(detail.contentAsString).isEqualTo(200)
            assertThat(mapper.readTree(detail.contentAsString).path("state").asString()).isEqualTo("EXPIRED")
            val list = request("GET", path, admin)
            assertThat(list.status).isEqualTo(200)
            assertThat(mapper.readTree(list.contentAsString).path("items").single().path("state").asString()).isEqualTo("EXPIRED")
        }
        WarehouseDraftExpiryFacts.rejected(request("POST", "/api/v1/warehouse/approvals/request", admin,
            """{"sourceDocumentId":"$id","sourceRevision":0}"""))
        assertThat(request("POST", path, admin, payload, "expiry-asset").contentAsString).isEqualTo(original.contentAsString)
        WarehouseDraftExpiryFacts.expire(database, id)
        assertThat(WarehouseDraftExpiryFacts.capture(database, id)).isEqualTo(before)
        // Title requests additionally deduplicate identical business payloads
        // across keys; a fresh review supplies its own reason.
        if (kind == "TITLE_CORRECTION") {
            val duplicate = request("POST", path, admin, payload, "expiry-duplicate")
            assertThat(duplicate.status).isEqualTo(409)
            assertThat(mapper.readTree(duplicate.contentAsString).path("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT")
        }
        val fresh = request("POST", path, admin, payload.replace("Signed title correction", "Renewed signed title review after expiry"), "expiry-fresh")
        assertThat(fresh.status).withFailMessage(fresh.contentAsString).isEqualTo(201)
    }
}
