package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.util.UUID

class WarehouseReceiptITMetadata : WarehouseReceiptHttpFixture() {
    @Test fun `attachment pages survive reload and flag replaced intake without leaking storage keys`() {
        val setup = setupReceipt()
        val lines = """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"EVIDENCE-PAGE"}"""
        val original = draft(setup, lines)
        val id = original.path("id").asString()
        assertThat(original.path("sourceLocationName").asString()).isEqualTo("Boundary")
        assertThat(original.path("inspectionLocationName").asString()).isEqualTo("Inspection")
        val path = "/api/v1/warehouse/receipts/$id/attachments"
        fun upload(revision: Int): String {
            val response = mvc.perform(multipart(path).file(MockMultipartFile("file", "proof.pdf", "application/pdf", ReceiptEvidenceFixtures.pdf()))
                .param("expectedRevision", revision.toString()).header("Authorization", "Bearer ${setup.token}")
                .header("Idempotency-Key", UUID.randomUUID().toString())).andReturn().response
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
            return mapper.readTree(response.contentAsString).path("id").asString()
        }
        val first = upload(0)
        val second = upload(1)
        fun page(number: Int = 0) = request("GET", "$path?page=$number&size=1", setup.token).also {
            assertThat(it.status).withFailMessage(it.contentAsString).isEqualTo(200)
            assertThat(it.contentAsString).doesNotContain("objectKey", "object_key", "intakeHash", "http://", "https://")
        }.let { mapper.readTree(it.contentAsString) }
        assertThat(page().path("totalElements").asLong()).isEqualTo(2)
        assertThat(page().path("items")[0].path("id").asString()).isEqualTo(second)
        assertThat(page(1).path("items")[0].path("id").asString()).isEqualTo(first)
        assertThat(page(2).path("items").size()).isZero()
        assertThat(page().path("items")[0].path("matchesCurrentIntake").asBoolean()).isTrue()
        val update = draftBody(setup, lines).replace("DELIVERY-1", "DELIVERY-2").dropLast(1) + ",\"expectedRevision\":2}"
        assertThat(request("PUT", "/api/v1/warehouse/receipts/$id", setup.token, update).status).isEqualTo(200)
        assertThat(page().path("items")[0].path("matchesCurrentIntake").asBoolean()).isFalse()
        assertThat(request("GET", "$path/$first", setup.token).status).isEqualTo(200)
        val third = upload(3)
        assertThat(page().path("items")[0].path("id").asString()).isEqualTo(third)
        assertThat(page().path("items")[0].path("matchesCurrentIntake").asBoolean()).isTrue()
        assertThat(page().path("totalElements").asLong()).isEqualTo(3)
        for (query in listOf("page=-1", "size=0", "size=101"))
            assertThat(request("GET", "$path?$query", setup.token).status).isEqualTo(400)
        assertThat(request("GET", path, tenant()).status).isEqualTo(404)
        assertThat(request("GET", path, null).status).isEqualTo(401)
        val (withoutPermission, _) = user(setup.token, setOf("inventory.sku.view"))
        assertThat(request("GET", path, withoutPermission).status).isEqualTo(403)
        val (withoutScope, _) = user(setup.token, setOf("inventory.receipt.view"))
        assertThat(request("GET", path, withoutScope).status).isEqualTo(404)
    }
}
