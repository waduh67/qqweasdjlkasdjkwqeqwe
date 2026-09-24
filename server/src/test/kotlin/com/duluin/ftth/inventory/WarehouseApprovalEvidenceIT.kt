package com.duluin.ftth.inventory

import com.duluin.ftth.common.storage.ObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.util.UUID

@Import(ReceiptRealStorage::class)
class WarehouseApprovalEvidenceIT : WarehouseApprovalHttpFixture() {
    @Autowired private lateinit var storage: ObjectStorage
    private val bytes = ReceiptEvidenceFixtures.image("png")
    private fun upload(setup: Setup, document: String, revision: Long): String {
        val response = mvc.perform(multipart("/api/v1/warehouse/receipts/$document/attachments")
            .file(MockMultipartFile("file", "proof.png", "image/png", bytes)).param("expectedRevision", revision.toString())
            .header("Authorization", "Bearer ${setup.token}").header("Idempotency-Key", UUID.randomUUID().toString())).andReturn().response
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return mapper.readTree(response.contentAsString).path("id").asString()
    }

    @Test fun `approver only downloads sealed intake evidence from real storage with bounded current authority reads`() {
        val setup = setupReceipt()
        val checker = approver(setup.token, listOf(setup.source, setup.inspection))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(checker.second)))
        val document = draft(setup, costLine(setup)).path("id").asString()
        val first = upload(setup, document, 0)
        val second = upload(setup, document, 1)
        val case = submit(setup, checker, document, 2)
        val root = "/api/v1/warehouse/approvals/${case.id}/attachments"
        val page = request("GET", "$root?size=1", checker.first)
        assertThat(page.status).withFailMessage(page.contentAsString).isEqualTo(200)
        assertThat(page.contentAsString).doesNotContain("object_key", "storage", "sha256", "cost", "http:")
        val value = mapper.readTree(page.contentAsString)
        assertThat(value.path("totalElements").asLong()).isEqualTo(2)
        assertThat(value.path("items").single().path("id").asString()).isEqualTo(second)
        assertThat(mapper.readTree(request("GET", "$root?size=1&page=1", checker.first).contentAsString).path("items").single().path("id").asString()).isEqualTo(first)
        val file = request("GET", "$root/$first", checker.first)
        assertThat(file.status).withFailMessage(file.contentAsString).isEqualTo(200)
        assertThat(file.contentAsByteArray).isEqualTo(bytes)
        assertThat(file.contentType).isEqualTo("image/png")
        assertThat(file.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(file.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(file.getHeader("Content-Disposition")).startsWith("attachment;")
        assertThat(request("GET", "/api/v1/warehouse/receipts/$document/attachments/$first", checker.first).status).isEqualTo(403)
        assertThat(request("GET", "$root/$first", tenant()).status).isEqualTo(404)
        assertThat(request("GET", "$root/${UUID.randomUUID()}", checker.first).status).isEqualTo(404)
        assertThat(request("GET", "$root?size=101", checker.first).status).isEqualTo(400)
        assertThat(request("GET", "$root?page=0&page=1", checker.first).status).isEqualTo(400)
        val tenant = fixture(setup.token).tenant
        val key = "$tenant/warehouse/receipts/$document/$first"
        storage.put(key, "image/png", byteArrayOf(1, 2, 3))
        try { assertThat(request("GET", "$root/$first", checker.first).status).isEqualTo(409) }
        finally { storage.put(key, "image/png", bytes) }
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${checker.second}/${setup.inspection}", setup.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(request("GET", root, checker.first).status).isEqualTo(404)
        assertThat(request("GET", "$root/$first", checker.first).status).isEqualTo(404)
    }

    @Test fun `old intake and later uploads are not added to a sealed approval evidence set`() {
        val setup = setupReceipt()
        val checker = approver(setup.token, listOf(setup.source, setup.inspection))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(checker.second)))
        val document = draft(setup, costLine(setup)).path("id").asString()
        val old = upload(setup, document, 0)
        val changed = draftBody(setup, costLine(setup, quantity = "4")).dropLast(1) + ",\"expectedRevision\":1}"
        assertThat(request("PUT", "/api/v1/warehouse/receipts/$document", setup.token, changed).status).isEqualTo(200)
        val current = upload(setup, document, 2)
        val case = submit(setup, checker, document, 3)
        val later = upload(setup, document, 3)
        val root = "/api/v1/warehouse/approvals/${case.id}/attachments"
        val page = request("GET", root, checker.first)
        assertThat(page.status).withFailMessage(page.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(page.contentAsString).path("items").map { it.path("id").asString() }).containsExactly(current)
        assertThat(request("GET", "$root/$old", checker.first).status).isEqualTo(404)
        assertThat(request("GET", "$root/$later", checker.first).status).isEqualTo(404)
        assertThat(request("GET", "$root/$current", checker.first).contentAsByteArray).isEqualTo(bytes)
    }
}
