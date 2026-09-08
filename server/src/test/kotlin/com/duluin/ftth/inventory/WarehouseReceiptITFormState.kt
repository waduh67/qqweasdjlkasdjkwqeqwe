package com.duluin.ftth.inventory

import com.duluin.ftth.common.storage.ObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart

@Import(ReceiptRealStorage::class)
class WarehouseReceiptITFormState : WarehouseReceiptHttpFixture() {
    @Autowired private lateinit var storage: ObjectStorage

    @ParameterizedTest @ValueSource(strings = ["EXACT", "RESET", "GRAY", "CMYK", "NAMED", "STROKE", "ISOLATED", "SHARED", "NESTED", "SIBLINGS", "DETACHED", "Q_RESTORE", "MATRIX", "NULL_MATRIX"])
    fun `valid Form invocation PDFs round trip byte identically`(kind: String) {
        val setup = setupReceipt()
        val id = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"FORM-STATE"}""").path("id").asString()
        val database = fixture(setup.token)
        val prefix = "${database.tenant}/warehouse/receipts/$id/"
        val bytes = ReceiptPdfFormFixtures.positive(kind)
        try {
            val response = mvc.perform(multipart("/api/v1/warehouse/receipts/$id/attachments")
                .file(MockMultipartFile("file", "form.pdf", "application/pdf", bytes)).param("expectedRevision", "0")
                .header("Authorization", "Bearer ${setup.token}").header("Idempotency-Key", "form-state")).andReturn().response
            assertThat(response.status).withFailMessage("$kind ${response.contentAsString}").isEqualTo(201)
            val evidence = mapper.readTree(response.contentAsString).path("id").asString()
            val downloaded = request("GET", "/api/v1/warehouse/receipts/$id/attachments/$evidence", setup.token)
            assertThat(downloaded.status).isEqualTo(200)
            assertThat(downloaded.contentAsByteArray).isEqualTo(bytes)
        } finally { storage.list(database.tenant.toString(), prefix).objects.forEach { storage.delete(it.key) } }
    }

    @ParameterizedTest @ValueSource(strings = ["GRAY_MISMATCH", "RGB_MISMATCH", "CMYK_MISMATCH", "NAMED_MISMATCH", "SECOND_INVOCATION", "CYCLE", "NESTED_CYCLE", "UNDERFLOW", "UNCLOSED", "BUDGET", "DEPTH", "MATRIX", "DETACHED_CONFLICT", "DETACHED_CHILD_CONFLICT"])
    fun `invalid invocation states cannot create evidence`(kind: String) {
        val setup = setupReceipt()
        val id = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"INVALID-FORM"}""").path("id").asString()
        val response = mvc.perform(multipart("/api/v1/warehouse/receipts/$id/attachments")
            .file(MockMultipartFile("file", "form.pdf", "application/pdf", ReceiptPdfFormFixtures.negative(kind))).param("expectedRevision", "0")
            .header("Authorization", "Bearer ${setup.token}").header("Idempotency-Key", "invalid-form")).andReturn().response
        assertThat(response.status).describedAs(kind).isEqualTo(400)
        assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("MALFORMED_REQUEST")
        val database = fixture(setup.token)
        database.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_receipt_evidence")).isEqualTo("0")
            assertThat(scalar("SELECT revision FROM inventory_document WHERE id='$id'")).isEqualTo("0")
        }
        assertThat(storage.list(database.tenant.toString(), "${database.tenant}/warehouse/receipts/$id/").objects).isEmpty()
    }
}
