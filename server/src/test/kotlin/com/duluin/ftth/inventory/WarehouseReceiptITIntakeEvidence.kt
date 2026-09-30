package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart

class WarehouseReceiptITIntakeEvidence : WarehouseReceiptHttpFixture() {
    @Test fun `evidence from serial intake cannot authorize replacement cable intake`() {
        val setup = setupReceipt()
        val original = draft(setup, """{"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"OLD-PROOF"}]}""")
        val id = original.path("id").asString()
        val evidence = upload(setup, id, 0)
        val replacement = draftBody(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"NEW-INTAKE"}""").dropLast(1) + ",\"expectedRevision\":1}"
        assertThat(request("PUT", "/api/v1/warehouse/receipts/$id", setup.token, replacement).status).isEqualTo(200)
        transition(setup, id, "receive", """{"expectedRevision":2}""")
        val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString).path("lines")[0]
        val body = """{"expectedRevision":3,"lines":[{"lineId":"${line.path("id").asString()}","stockIdentityId":"${line.path("pieces")[0].path("stockIdentityId").asString()}",
            "baseUnit":"MM","acceptedBase":"1000","rejectedBase":"0","evidenceId":"$evidence","reason":"Old document"}]}"""
        assertThat(request("POST", "/api/v1/warehouse/receipts/$id/inspect", setup.token, body).status).isEqualTo(409)
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_receipt_disposition")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_inspection")).isEqualTo("0")
            assertThat(scalar("SELECT revision FROM inventory_document WHERE id='$id'")).isEqualTo("3")
        }
    }

    @Test fun `unchanged intake evidence survives attachment replay receive and multiple inspections`() {
        val setup = setupReceipt()
        val id = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"REUSE"}""").path("id").asString()
        val proof = upload(setup, id, 0)
        assertThat(upload(setup, id, 0)).isEqualTo(proof)
        transition(setup, id, "receive", """{"expectedRevision":1}""")
        for ((revision, amount) in listOf(2 to "400", 3 to "600")) {
            val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString).path("lines")[0]
            val piece = line.path("pieces").single { it.path("disposition").isNull }
            transition(setup, id, "inspect", """{"expectedRevision":$revision,"lines":[{"lineId":"${line.path("id").asString()}",
                "stockIdentityId":"${piece.path("stockIdentityId").asString()}","baseUnit":"MM","acceptedBase":"$amount","rejectedBase":"0","evidenceId":"$proof","reason":"Measured"}]}""")
        }
        fixture(setup.token).transaction { assertThat(scalar("SELECT sum(accepted_base) FROM inventory_inspection")).isEqualTo("1000") }
    }

    private fun upload(setup: Setup, id: String, revision: Long): String {
        val response = mvc.perform(multipart("/api/v1/warehouse/receipts/$id/attachments")
            .file(MockMultipartFile("file", "proof.pdf", "application/pdf", ReceiptEvidenceFixtures.pdf()))
            .param("expectedRevision", revision.toString()).header("Authorization", "Bearer ${setup.token}")
            .header("Idempotency-Key", "versioned-evidence")).andReturn().response
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return mapper.readTree(response.contentAsString).path("id").asString()
    }
}
