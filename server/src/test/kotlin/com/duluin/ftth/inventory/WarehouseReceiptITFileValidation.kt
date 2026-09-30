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
class WarehouseReceiptITFileValidation : WarehouseReceiptHttpFixture() {
    @Autowired private lateinit var storage: ObjectStorage

    @ParameterizedTest
    @ValueSource(strings = ["PDF_PREFIX", "PDF_HTML", "PDF_NO_EOF", "PDF_NO_XREF", "PDF_NO_TRAILER", "PDF_TRAILING_HTML", "PDF_JAVASCRIPT", "PNG_TRUNCATED", "PNG_BAD_CRC", "PNG_TRAILING_HTML", "JPEG_TRUNCATED", "JPEG_TRAILING_HTML", "JPEG_CONCATENATED", "MIME_MISMATCH", "EMPTY"])
    fun `malformed documents never become evidence`(kind: String) {
        val setup = setupReceipt()
        val id = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"PARSE"}""").path("id").asString()
        val pdf = ReceiptEvidenceFixtures.pdf()
        val mime = when { kind.startsWith("PNG") -> "image/png"; kind.startsWith("JPEG") -> "image/jpeg"; else -> "application/pdf" }
        val bytes = when (kind) {
            "PDF_PREFIX" -> "%PDF-".toByteArray()
            "PDF_HTML" -> "%PDF-1.4\n<html><script>alert(1)</script></html>".toByteArray()
            "PDF_NO_EOF" -> pdf.copyOf(pdf.size - 7)
            "PDF_NO_XREF" -> pdf.toString(Charsets.US_ASCII).replace("xref\n0 5", "xxxx\n0 5").toByteArray()
            "PDF_NO_TRAILER" -> pdf.toString(Charsets.US_ASCII).replace("trailer", "xxxxxxx").toByteArray()
            "PDF_TRAILING_HTML" -> pdf + "<html><script>alert(1)</script></html>".toByteArray()
            "PDF_JAVASCRIPT" -> ReceiptEvidenceFixtures.pdf("/OpenAction << /S /JavaScript /JS (app.alert\\(1\\)) >>")
            "PNG_TRUNCATED" -> ReceiptEvidenceFixtures.image("png").copyOf(16)
            "PNG_BAD_CRC" -> ReceiptEvidenceFixtures.image("png").also { it[29] = (it[29].toInt() xor 1).toByte() }
            "PNG_TRAILING_HTML" -> ReceiptEvidenceFixtures.image("png") + "<html>bad</html>".toByteArray()
            "JPEG_TRUNCATED" -> ReceiptEvidenceFixtures.image("jpeg").let { it.copyOf(it.size - 2) }
            "JPEG_TRAILING_HTML" -> ReceiptEvidenceFixtures.image("jpeg") + "<html>bad</html>".toByteArray()
            "JPEG_CONCATENATED" -> ReceiptEvidenceFixtures.image("jpeg") + ReceiptEvidenceFixtures.image("jpeg")
            "MIME_MISMATCH" -> ReceiptEvidenceFixtures.image("png")
            "EMPTY" -> byteArrayOf()
            else -> error("Unknown fixture")
        }
        val database = fixture(setup.token)
        try {
            val response = upload(setup, id, mime, bytes)
            assertThat(response.status).describedAs(kind).isEqualTo(400)
            assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("MALFORMED_REQUEST")
            database.transaction { assertThat(scalar("SELECT count(*) FROM inventory_receipt_evidence")).isEqualTo("0") }
            assertThat(storage.list(database.tenant.toString(), "${database.tenant}/warehouse/receipts/$id/").objects).isEmpty()
        } finally { storage.list(database.tenant.toString(), "${database.tenant}/warehouse/receipts/$id/").objects.forEach { storage.delete(it.key) } }
    }

    @ParameterizedTest @ValueSource(strings = ["png", "jpeg", "pdf"])
    fun `valid parsed images and PDF retain exact private object bytes`(format: String) {
        val setup = setupReceipt()
        val id = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"VALID"}""").path("id").asString()
        val bytes = if (format == "pdf") ReceiptEvidenceFixtures.pdf() else ReceiptEvidenceFixtures.image(format)
        val mime = if (format == "pdf") "application/pdf" else "image/$format"
        val database = fixture(setup.token)
        try {
            val response = upload(setup, id, mime, bytes)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
            val evidence = mapper.readTree(response.contentAsString).path("id").asString()
            assertThat(storage.get("${database.tenant}/warehouse/receipts/$id/$evidence").bytes).isEqualTo(bytes)
        } finally { storage.list(database.tenant.toString(), "${database.tenant}/warehouse/receipts/$id/").objects.forEach { storage.delete(it.key) } }
    }

    private fun upload(setup: Setup, id: String, type: String, bytes: ByteArray) = mvc.perform(multipart("/api/v1/warehouse/receipts/$id/attachments")
        .file(MockMultipartFile("file", "proof", type, bytes)).param("expectedRevision", "0")
        .header("Authorization", "Bearer ${setup.token}").header("Idempotency-Key", "parse-proof")).andReturn().response
}
