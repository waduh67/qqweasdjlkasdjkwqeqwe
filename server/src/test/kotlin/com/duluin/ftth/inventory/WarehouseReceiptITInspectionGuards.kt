package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.util.UUID

class WarehouseReceiptITInspectionGuards : WarehouseReceiptHttpFixture() {
    @Test fun `partial inspection and partial putaway retain pending quantity and reject overage wrong unit and foreign evidence`() {
        val setup = setupReceipt()
        val draft = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"PARTIAL"}""")
        val id = draft.path("id").asString()
        transition(setup, id, "receive", """{"expectedRevision":0}""")
        val evidenceId = proof(setup, id, 1)
        val lineId = draft.path("lines")[0].path("id").asString()
        fun detail() = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString)
        val original = detail().path("lines")[0].path("pieces")[0].path("stockIdentityId").asString()
        fun inspect(piece: String, accepted: String, rejected: String, revision: Int, evidence: String = evidenceId, unit: String = "MM") =
            """{"expectedRevision":$revision,"lines":[{"lineId":"$lineId","stockIdentityId":"$piece","baseUnit":"$unit","acceptedBase":"$accepted","rejectedBase":"$rejected","evidenceId":"$evidence","reason":"Measured"}]}"""
        fun putaway(piece: String, quantity: String, revision: Int, destination: String = setup.bin) =
            """{"expectedRevision":$revision,"destinationLocationId":"$destination","lines":[{"lineId":"$lineId","stockIdentityId":"$piece","baseUnit":"MM","quantityBase":"$quantity"}]}"""
        assertThat(request("POST", "/api/v1/warehouse/receipts/$id/putaway", setup.token, putaway(original, "1000", 2)).status).isEqualTo(409)
        assertThat(request("POST", "/api/v1/warehouse/receipts/$id/inspect", setup.token, inspect(original, "1001", "0", 2)).status).isEqualTo(409)
        assertThat(request("POST", "/api/v1/warehouse/receipts/$id/inspect", setup.token, inspect(original, "1", "0", 2, unit = "EA")).status).isEqualTo(400)
        assertThat(request("POST", "/api/v1/warehouse/receipts/$id/inspect", setup.token, inspect(original, "1", "0", 2, evidence = UUID.randomUUID().toString())).status).isEqualTo(404)
        transition(setup, id, "inspect", inspect(original, "400", "100", 2))
        val pieces = detail().path("lines")[0].path("pieces")
        val accepted = pieces.single { it.path("disposition").asString() == "ACCEPTED" }.path("stockIdentityId").asString()
        val rejected = pieces.single { it.path("disposition").asString() == "QUARANTINE" }.path("stockIdentityId").asString()
        val pending = pieces.single { it.path("disposition").isNull }.path("stockIdentityId").asString()
        assertThat(request("POST", "/api/v1/warehouse/receipts/$id/putaway", setup.token, putaway(rejected, "100", 3)).status).isEqualTo(409)
        assertThat(request("POST", "/api/v1/warehouse/receipts/$id/putaway", setup.token, putaway(accepted, "401", 3)).status).isEqualTo(409)
        assertThat(request("POST", "/api/v1/warehouse/receipts/$id/putaway", setup.token, putaway(accepted, "1", 3, setup.inspection)).status).isEqualTo(409)
        transition(setup, id, "putaway", putaway(accepted, "200", 3))
        assertThat(detail().path("state").asString()).isEqualTo("RECEIVED_IN_INSPECTION")
        transition(setup, id, "inspect", inspect(pending, "200", "0", 4))
        val final = detail()
        assertThat(final.path("lines")[0].path("acceptedBase").asString()).isEqualTo("600")
        assertThat(final.path("lines")[0].path("rejectedBase").asString()).isEqualTo("100")
        assertThat(final.path("lines")[0].path("putawayBase").asString()).isEqualTo("200")
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection")).isEqualTo("1000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("200")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='QUARANTINE'")).isEqualTo("800")
        }
    }

    @Test fun `only explicit snapshotted inspection bypass permits putaway and never automatic availability`() {
        val setup = setupReceipt()
        assertThat(request("PUT", "/api/v1/warehouse/skus/${setup.cable}", setup.token,
            """{"code":"CABLE","name":"Cable","tracking":"LOT","baseUnit":"MM","inspectionRequired":false,"expectedRevision":0}""").status).isEqualTo(200)
        val draft = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"BYPASS"}""")
        val id = draft.path("id").asString()
        assertThat(request("PUT", "/api/v1/warehouse/skus/${setup.cable}", setup.token,
            """{"code":"CABLE","name":"Cable","tracking":"LOT","baseUnit":"MM","inspectionRequired":true,"expectedRevision":1}""").status).isEqualTo(200)
        transition(setup, id, "receive", """{"expectedRevision":0}""")
        fixture(setup.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("0") }
        val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString).path("lines")[0]
        assertThat(line.path("inspectionRequired").asBoolean()).isFalse()
        transition(setup, id, "putaway", """{"expectedRevision":1,"destinationLocationId":"${setup.bin}","lines":[{"lineId":"${line.path("id").asString()}",
            "stockIdentityId":"${line.path("pieces")[0].path("stockIdentityId").asString()}","baseUnit":"MM","quantityBase":"1000"}]}""")
        fixture(setup.token).transaction { assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("1000") }
    }

    private fun proof(setup: Setup, id: String, revision: Int): String {
        val response = mvc.perform(multipart("/api/v1/warehouse/receipts/$id/attachments")
            .file(MockMultipartFile("file", "proof.pdf", "application/pdf", ReceiptEvidenceFixtures.pdf()))
            .param("expectedRevision", revision.toString()).header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Authorization", "Bearer ${setup.token}")).andReturn().response
        assertThat(response.status).isEqualTo(201)
        return mapper.readTree(response.contentAsString).path("id").asString()
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["CONDITION", "OWNER"])
    fun `changed stock condition or owner cannot be released by receipt putaway`(change: String) {
        val setup = setupReceipt()
        val draft = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"DIRTY"}""")
        val id = draft.path("id").asString()
        transition(setup, id, "receive", """{"expectedRevision":0}""")
        val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString).path("lines")[0]
        val piece = line.path("pieces")[0]
        val dimension = com.duluin.ftth.inventory.application.port.outbound.PostingDimension(UUID.fromString(setup.cable),
            UUID.fromString(piece.path("stockIdentityId").asString()), UUID.fromString(piece.path("lotId").asString()),
            UUID.fromString(setup.inspection), UUID.fromString(setup.inspection), com.duluin.ftth.inventory.domain.model.OwnerKind.WAREHOUSE,
            WarehouseCondition.QUARANTINE, AssetLegalOwner.ISP)
        fixture(setup.token).transaction {
            val target = if (change == "CONDITION") dimension.copy(condition = WarehouseCondition.DAMAGED) else dimension.copy(legalOwner = AssetLegalOwner.CUSTOMER)
            post(move(dimension, target, com.duluin.ftth.inventory.domain.model.StockQuantity.metres("1")))
        }
        val body = """{"expectedRevision":1,"destinationLocationId":"${setup.bin}","lines":[{"lineId":"${line.path("id").asString()}",
            "stockIdentityId":"${piece.path("stockIdentityId").asString()}","baseUnit":"MM","quantityBase":"1000"}]}"""
        assertThat(request("POST", "/api/v1/warehouse/receipts/$id/putaway", setup.token, body).status).isEqualTo(409)
    }
}
