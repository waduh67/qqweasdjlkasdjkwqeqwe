package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import tools.jackson.databind.JsonNode

class WarehouseReceiptITCompletion : WarehouseReceiptHttpFixture() {
    @Test fun `final pending rejection completes a receipt whose accepted stock was already put away`() {
        val setup = setupReceipt()
        val id = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"FINISH"}""").path("id").asString()
        transition(setup, id, "receive", """{"expectedRevision":0}""")
        val proof = proof(setup, id)
        val initial = line(setup, id)
        transition(setup, id, "inspect", inspect(initial, initial.path("pieces")[0], proof, 2, "600", "100"))
        for ((revision, amount) in listOf(3 to "200", 4 to "400")) {
            val current = line(setup, id)
            val accepted = current.path("pieces").single { it.path("disposition").asString() == "ACCEPTED" && it.path("status").asString() == "QUARANTINE" }
            transition(setup, id, "putaway", """{"expectedRevision":$revision,"destinationLocationId":"${setup.bin}","lines":[{
                "lineId":"${current.path("id").asString()}","stockIdentityId":"${accepted.path("stockIdentityId").asString()}","baseUnit":"MM","quantityBase":"$amount"}]}""")
        }
        val current = line(setup, id)
        val body = inspect(current, current.path("pieces").single { it.path("disposition").isNull }, proof, 5, "0", "300")
        val finished = transition(setup, id, "inspect", body, "final-reject")
        assertThat(finished.path("state").asString()).isEqualTo("PUTAWAY")
        assertThat(finished.path("revision").asLong()).isEqualTo(6)
        assertThat(transition(setup, id, "inspect", body, "final-reject")).isEqualTo(finished)
        val result = line(setup, id)
        assertThat(result.path("acceptedBase").asString()).isEqualTo("600")
        assertThat(result.path("rejectedBase").asString()).isEqualTo("400")
        assertThat(result.path("putawayBase").asString()).isEqualTo("600")
        fixture(setup.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("4") }
    }

    @ParameterizedTest @ValueSource(strings = ["SERIAL", "LOT"])
    fun `fully rejected receipt closes without putaway or a phantom movement`(tracking: String) {
        val setup = setupReceipt()
        val input = if (tracking == "SERIAL") """{"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"REJECT-ALL"}]}"""
            else """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"REJECT-ALL"}"""
        val id = draft(setup, input).path("id").asString()
        transition(setup, id, "receive", """{"expectedRevision":0}""")
        val proof = proof(setup, id)
        val current = line(setup, id)
        val body = inspect(current, current.path("pieces")[0], proof, 2, "0", current.path("quantityBase").asString())
        val closed = transition(setup, id, "inspect", body, "reject-all")
        assertThat(closed.path("state").asString()).isEqualTo("CLOSED")
        assertThat(transition(setup, id, "inspect", body, "reject-all")).isEqualTo(closed)
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("0")
        }
    }

    private fun line(setup: Setup, id: String) = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString).path("lines")[0]
    private fun inspect(line: JsonNode, piece: JsonNode, proof: String, revision: Int, accepted: String, rejected: String) =
        """{"expectedRevision":$revision,"lines":[{"lineId":"${line.path("id").asString()}","stockIdentityId":"${piece.path("stockIdentityId").asString()}",
            "baseUnit":"${line.path("baseUnit").asString()}","acceptedBase":"$accepted","rejectedBase":"$rejected","evidenceId":"$proof","reason":"Disposition"}]}"""
    private fun proof(setup: Setup, id: String): String {
        val response = mvc.perform(multipart("/api/v1/warehouse/receipts/$id/attachments")
            .file(MockMultipartFile("file", "proof.pdf", "application/pdf", ReceiptEvidenceFixtures.pdf())).param("expectedRevision", "1")
            .header("Authorization", "Bearer ${setup.token}").header("Idempotency-Key", "completion-proof")).andReturn().response
        assertThat(response.status).isEqualTo(201)
        return mapper.readTree(response.contentAsString).path("id").asString()
    }
}
