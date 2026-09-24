package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseApprovalWorkbenchWorkOrderIT : WarehouseReturnAssetFixture() {
    @Test fun `queue applies original work order authority before page totals and rolls back denied visibility transactions`() {
        val returned = recoveredReturn("SALE")
        val receipt = returned.old.installation.receipt
        val admin = receipt.stock.token
        val checker = user(admin, setOf("inventory.approval.view", "inventory.approval.decide"))
        val principal = mapper.readTree(request("GET", "/api/users/${checker.second}", admin).contentAsString)
        assertThat(request("PUT", "/api/users/${checker.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${checker.second}/${returned.quarantine}", admin,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        assertThat(request("PUT", "/api/v1/warehouse/settings/policy", admin,
            """{"expectedRevision":0,"currency":"IDR","expiryHours":24,"warehouseIds":["${returned.quarantine}"],"rules":[{"operation":"TITLE_REACQUISITION","tiers":[{"minimumMinor":"1","userIds":["${checker.second}"],"roleIds":[]}]}]}""").status).isEqualTo(200)
        val correction = request("POST", "/api/v1/warehouse/returns/${returned.id}/reacquisition", admin,
            """{"expectedRevision":${returned.revision},"reason":"Signed customer return","titleTransferReference":"TRANSFER-TITLE","evidenceId":"${returned.old.signature}"}""")
        assertThat(correction.status).withFailMessage(correction.contentAsString).isEqualTo(201)
        val document = mapper.readTree(correction.contentAsString).path("documentId").asString()
        val pending = request("POST", "/api/v1/warehouse/approvals/request", admin, """{"sourceDocumentId":"$document","sourceRevision":0}""")
        assertThat(pending.status).withFailMessage(pending.contentAsString).isEqualTo(201)
        val id = mapper.readTree(pending.contentAsString).path("requestId").asString()
        val detail = request("GET", "/api/v1/warehouse/approvals/$id/details", checker.first)
        assertThat(detail.status).withFailMessage(detail.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(detail.contentAsString).path("document").path("returnId").asString()).isEqualTo(returned.id)
        assertThat(detail.contentAsString).doesNotContain("\"cost\"", "signature", "storageKey", "sha256", "payload_hash")
        assertThat(mapper.readTree(detail.contentAsString).path("document").path("evidenceReferences").single().path("reference").asString()).isEqualTo("TRANSFER-TITLE")
        val files = "/api/v1/warehouse/approvals/$id/attachments"
        val listed = request("GET", files, checker.first)
        assertThat(listed.status).withFailMessage(listed.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(listed.contentAsString).path("items").single().path("id").asString()).isEqualTo(returned.old.signature.toString())
        assertThat(listed.contentAsString).doesNotContain("reference", "digest", "workOrderId", "storageKey", "sha256")
        val image = request("GET", "$files/${returned.old.signature}", checker.first)
        assertThat(image.status).withFailMessage(image.contentAsString).isEqualTo(200)
        assertThat(image.contentType).isEqualTo("image/png")
        assertThat(image.contentAsByteArray).isEqualTo(request("GET", "/api/work-orders/${receipt.workOrder}/signature/content", admin).contentAsByteArray)
        assertThat(request("GET", "/api/work-orders/${receipt.workOrder}/signature/content", checker.first).status).isIn(403, 404)
        assertThat(request("GET", "$files/${java.util.UUID.randomUUID()}", checker.first).status).isEqualTo(404)
        val initial = request("GET", "/api/v1/warehouse/approvals/workbench?size=1", checker.first)
        assertThat(initial.status).withFailMessage(initial.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(initial.contentAsString).path("totalElements").asLong()).isEqualTo(1)
        val otherArea = request("POST", "/api/areas", admin, """{"code":"OTHER-WO","name":"Other work order area"}""")
        assertThat(otherArea.status).isEqualTo(201)
        val hiddenArea = mapper.readTree(otherArea.contentAsString).path("id").asString()
        // Deliberate authority-change fixture: warehouse locations/grants stay visible.
        fixture(admin).transaction { sql("UPDATE work_order SET area_id='$hiddenArea' WHERE id='${receipt.workOrder}'") }
        for (path in listOf("/workbench?size=1", "?size=1", "/workbench?sourceDocumentId=$document")) {
            val denied = request("GET", "/api/v1/warehouse/approvals$path", checker.first)
            assertThat(denied.status).withFailMessage(denied.contentAsString).isEqualTo(200)
            assertThat(mapper.readTree(denied.contentAsString).path("totalElements").asLong()).isZero()
            assertThat(mapper.readTree(denied.contentAsString).path("items").size()).isZero()
        }
        assertThat(request("GET", "/api/v1/warehouse/approvals/$id/details", checker.first).status).isEqualTo(404)
        assertThat(request("GET", "/api/v1/warehouse/approvals/sources/$document", checker.first).status).isEqualTo(404)
        assertThat(request("GET", files, checker.first).status).isEqualTo(404)
        assertThat(request("GET", "$files/${returned.old.signature}", checker.first).status).isEqualTo(404)
    }
}
