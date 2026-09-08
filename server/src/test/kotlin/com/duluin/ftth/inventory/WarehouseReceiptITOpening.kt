package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart

class WarehouseReceiptITOpening : WarehouseMasterHttpFixture() {
    @Test fun `opening balance requires independent approval and cannot impersonate supplier receipt`() {
        val token = tenant()
        val (viewer, _) = user(token, setOf("inventory.receipt.manage"))
        val input = """{"migrationReference":"MIGRATION-1","sourceSnapshot":"Physical count sheet 1","cutoff":"2026-01-01T00:00:00Z"}"""
        fun opening(actor: String, body: String = input, type: String = "application/pdf") = mvc.perform(multipart("/api/v1/warehouse/opening-balances/requests")
            .file(MockMultipartFile("file", "count.pdf", type, "%PDF-1.4\ncount\n%%EOF".toByteArray()))
            .param("request", body).header("Idempotency-Key", "opening-key").header("Authorization", "Bearer $actor")).andReturn().response
        assertThat(opening(viewer).status).isEqualTo(403)
        assertThat(opening(token, type = "image/png").status).isEqualTo(400)
        assertThat(opening(token, input.dropLast(1) + ",\"approved\":true}").status).isEqualTo(400)
        val response = opening(token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("INDEPENDENT_APPROVER_REQUIRED")
        fixture(token).transaction {
            for (table in listOf("inventory_document", "inventory_identity_claim", "inventory_segment", "inventory_movement", "inventory_balance_projection"))
                assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo("0")
        }
    }
}
