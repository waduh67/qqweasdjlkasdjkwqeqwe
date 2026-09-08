package com.duluin.ftth.inventory

import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReceiptEvidencePersistence
import com.duluin.ftth.inventory.adapter.outbound.persistence.StoredReceiptEvidence
import com.duluin.ftth.inventory.application.port.inbound.ReceiptEvidenceView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.security.MessageDigest
import java.util.UUID

@Import(ReceiptRealStorage::class)
class WarehouseReceiptITPdfSyntax : WarehouseReceiptHttpFixture() {
    @Autowired private lateinit var storage: ObjectStorage
    @Autowired private lateinit var evidence: ReceiptEvidencePersistence

    @ParameterizedTest @ValueSource(strings = ["RAW_GAP:UPLOAD", "CONTENT_HTML:UPLOAD", "RAW_GAP:OPENING", "CONTENT_HTML:OPENING", "RAW_GAP:INSPECT", "CONTENT_HTML:INSPECT"])
    fun `malformed PDF syntax cannot create or authorize receipt evidence`(scenario: String) {
        val (kind, action) = scenario.split(':')
        val bytes = if (kind == "RAW_GAP") ReceiptPdfSyntaxFixtures.rawGap() else ReceiptPdfSyntaxFixtures.classic("<html><script>alert(1)</script></html>")
        val setup = setupReceipt()
        val receipt = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"PDF-SYNTAX"}""")
        val id = receipt.path("id").asString()
        val database = fixture(setup.token)
        val prefix = "${database.tenant}/warehouse/receipts/$id/"
        try {
            val response = when (action) {
                "UPLOAD" -> upload(setup, id, bytes)
                "OPENING" -> mvc.perform(multipart("/api/v1/warehouse/opening-balances/requests")
                    .file(MockMultipartFile("file", "proof.pdf", "application/pdf", bytes))
                    .param("request", """{"migrationReference":"COUNT","sourceSnapshot":"Count sheet","cutoff":"2026-01-01T00:00:00Z"}""")
                    .header("Authorization", "Bearer ${setup.token}").header("Idempotency-Key", "opening-pdf")).andReturn().response
                "INSPECT" -> {
                    transition(setup, id, "receive", """{"expectedRevision":0}""")
                    val evidenceId = UUID.randomUUID()
                    val actor = UUID.fromString(mapper.readTree(request("GET", "/api/me", setup.token).contentAsString).path("id").asString())
                    storage.put(prefix + evidenceId, "application/pdf", bytes)
                    database.transaction { evidence.save(StoredReceiptEvidence(ReceiptEvidenceView(evidenceId, UUID.fromString(id), "application/pdf", bytes.size.toLong(),
                        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }), prefix + evidenceId, evidence.currentBinding(UUID.fromString(id))), actor) }
                    val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString).path("lines")[0]
                    request("POST", "/api/v1/warehouse/receipts/$id/inspect", setup.token, """{"expectedRevision":1,"lines":[{"lineId":"${line.path("id").asString()}",
                        "stockIdentityId":"${line.path("pieces")[0].path("stockIdentityId").asString()}","baseUnit":"MM","acceptedBase":"1000","rejectedBase":"0","evidenceId":"$evidenceId","reason":"Legacy proof revalidation"}]}""")
                }
                else -> error("Unknown action")
            }
            assertThat(response.status).withFailMessage("$scenario: ${response.contentAsString}").isEqualTo(400)
            assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("MALFORMED_REQUEST")
            database.transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_receipt_evidence")).isEqualTo(if (action == "INSPECT") "1" else "0")
                assertThat(scalar("SELECT count(*) FROM inventory_inspection")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_receipt_disposition")).isEqualTo("0")
            }
        } finally { storage.list(database.tenant.toString(), prefix).objects.forEach { storage.delete(it.key) } }
    }

    @ParameterizedTest @ValueSource(strings = ["EMPTY", "TEXT", "HTML_LITERAL", "VECTOR", "COMMENTS", "INLINE_IMAGE", "INLINE_CRLF", "INLINE_FLATE", "COMPATIBILITY", "FORM_IMAGE", "INHERITED_FORM", "INCREMENTAL"])
    fun `valid diverse PDFs upload and download byte identically`(kind: String) {
        val setup = setupReceipt()
        val id = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"VALID-PDF"}""").path("id").asString()
        val database = fixture(setup.token)
        val prefix = "${database.tenant}/warehouse/receipts/$id/"
        val bytes = ReceiptPdfSyntaxFixtures.positive(kind)
        try {
            val response = upload(setup, id, bytes)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
            val evidenceId = mapper.readTree(response.contentAsString).path("id").asString()
            val download = request("GET", "/api/v1/warehouse/receipts/$id/attachments/$evidenceId", setup.token)
            assertThat(download.status).isEqualTo(200)
            assertThat(download.contentAsByteArray).isEqualTo(bytes)
        } finally { storage.list(database.tenant.toString(), prefix).objects.forEach { storage.delete(it.key) } }
    }

    private fun upload(setup: Setup, id: String, bytes: ByteArray) = mvc.perform(multipart("/api/v1/warehouse/receipts/$id/attachments")
        .file(MockMultipartFile("file", "proof.pdf", "application/pdf", bytes)).param("expectedRevision", "0")
        .header("Authorization", "Bearer ${setup.token}").header("Idempotency-Key", "syntax-proof")).andReturn().response
}
