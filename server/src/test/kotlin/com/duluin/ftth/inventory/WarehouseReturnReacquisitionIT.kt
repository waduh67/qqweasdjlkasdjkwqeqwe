package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseReturnReacquisitionIT : WarehouseReturnAssetFixture() {
    @Test fun `customer asset in quarantine requires independent reacquisition then reset inspection before ISP release`() {
        val returned = recoveredReturn("SALE")
        val receipt = returned.old.installation.receipt
        val admin = receipt.stock.token
        val asset = receipt.input.lines.single().stockIdentityId
        val checker = user(admin, setOf("inventory.approval.view", "inventory.approval.decide"))
        val principal = mapper.readTree(request("GET", "/api/users/${checker.second}", admin).contentAsString)
        assertThat(request("PUT", "/api/users/${checker.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${checker.second}/${returned.quarantine}", admin,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val policy = request("PUT", "/api/v1/warehouse/settings/policy", admin,
            """{"expectedRevision":0,"currency":"IDR","expiryHours":24,"warehouseIds":["${returned.quarantine}"],"rules":[{"operation":"TITLE_REACQUISITION","tiers":[{"minimumMinor":"1","userIds":["${checker.second}"],"roleIds":[]}]}]}""")
        assertThat(policy.status).withFailMessage(policy.contentAsString).isEqualTo(200)
        val path = "/api/v1/warehouse/returns/${returned.id}"
        val body = """{"expectedRevision":${returned.revision},"reason":"Customer transfers returned device to ISP","titleTransferReference":"signed-customer-reacquisition","evidenceId":"${returned.old.signature}"}"""
        val correction = request("POST", "$path/reacquisition", admin, body, "returned-title-request")
        assertThat(correction.status).withFailMessage(correction.contentAsString).isEqualTo(201)
        assertThat(request("POST", "$path/reacquisition", admin, body, "returned-title-request").contentAsString).isEqualTo(correction.contentAsString)
        val document = mapper.readTree(correction.contentAsString).path("documentId").asString()
        val second = request("POST", "$path/reacquisition", admin, body, "returned-title-second-document")
        assertThat(second.status).withFailMessage(second.contentAsString).isEqualTo(201)
        val secondDocument = mapper.readTree(second.contentAsString).path("documentId").asString()
        val listPath = "$path/reacquisition-requests"
        val firstPage = request("GET", "$listPath?size=1", admin)
        assertThat(firstPage.status).withFailMessage(firstPage.contentAsString).isEqualTo(200)
        val firstItems = mapper.readTree(firstPage.contentAsString)
        assertThat(firstItems.path("totalElements").asLong()).isEqualTo(2)
        val secondPage = mapper.readTree(request("GET", "$listPath?page=1&size=1", admin).contentAsString)
        assertThat(listOf(firstItems.path("items").single().path("documentId").asString(),
            secondPage.path("items").single().path("documentId").asString())).containsExactlyInAnyOrder(document, secondDocument)
        val entry = firstItems.path("items").single()
        assertThat(entry.path("code").asString()).startsWith("RET-TITLE-")
        assertThat(entry.path("returnId").asString()).isEqualTo(returned.id)
        assertThat(entry.path("sourceReturnRevision").asLong()).isEqualTo(returned.revision)
        assertThat(entry.path("evidenceId").asString()).isEqualTo(returned.old.signature.toString())
        assertThat(entry.path("titleTransferReference").asString()).isEqualTo("signed-customer-reacquisition")
        assertThat(entry.path("appliedReturnRevision").isNull).isTrue()
        assertThat(request("GET", "$listPath?page=2&size=1", admin).contentAsString).contains("\"items\":[]")
        for (suffix in listOf("size=101", "page=-1", "page=0&page=1", "query=secret"))
            assertThat(request("GET", "$listPath?$suffix", admin).status).isEqualTo(400)
        assertThat(request("GET", listPath, checker.first).status).isEqualTo(403)
        assertThat(request("GET", listPath, tenant()).status).isEqualTo(404)
        val reader = user(admin, setOf("inventory.return.view", "inventory.approval.view"))
        val readerPrincipal = mapper.readTree(request("GET", "/api/users/${reader.second}", admin).contentAsString)
        assertThat(request("PUT", "/api/users/${reader.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to readerPrincipal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        assertThat(request("GET", listPath, reader.first).status).isEqualTo(404)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${reader.second}/${returned.quarantine}", admin,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        assertThat(request("GET", "$listPath?size=1", reader.first).contentAsString).isEqualTo(firstPage.contentAsString)
        val approval = request("POST", "/api/v1/warehouse/approvals/request", admin,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", "returned-title-approval")
        assertThat(approval.status).withFailMessage(approval.contentAsString).isEqualTo(201)
        val approvalId = mapper.readTree(approval.contentAsString).path("requestId").asString()
        val decision = """{"requestId":"$approvalId","expectedRevision":0,"decision":"APPROVE","reason":"Independent title proof verified"}"""
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", admin, decision, "returned-self-approval").status).isEqualTo(403)
        fixture(admin).transaction {
            assertThat(scalar("SELECT concat_ws('|',legal_owner,status,condition) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("CUSTOMER|QUARANTINE|DAMAGED")
        }
        val approved = request("POST", "/api/v1/warehouse/approvals/decide", checker.first, decision, "returned-title-decision")
        assertThat(approved.status).withFailMessage(approved.contentAsString).isEqualTo(200)
        val view = request("GET", path, admin)
        assertThat(view.status).withFailMessage(view.contentAsString).isEqualTo(200)
        val current = mapper.readTree(view.contentAsString)
        assertThat(current.path("legalOwner").asString()).isEqualTo("ISP")
        assertThat(current.path("revision").asLong()).isEqualTo(returned.revision + 1)
        val appliedList = request("GET", listPath, reader.first)
        assertThat(appliedList.status).withFailMessage(appliedList.contentAsString).isEqualTo(200)
        val entries = mapper.readTree(appliedList.contentAsString).path("items").associateBy { it.path("documentId").asString() }
        assertThat(entries.getValue(document).path("appliedReturnRevision").asLong()).isEqualTo(returned.revision + 1)
        assertThat(entries.getValue(secondDocument).path("appliedReturnRevision").isNull).isTrue()
        val details = request("GET", "$path/details", admin)
        assertThat(mapper.readTree(details.contentAsString).path("references").path("assetOrigin").path("legalOwner").asString()).isEqualTo("CUSTOMER")
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${reader.second}/${returned.quarantine}", admin,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(request("GET", listPath, reader.first).status).isEqualTo(404)
        fixture(admin).transaction {
            assertThat(scalar("SELECT concat_ws('|',legal_owner,status,condition) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("ISP|QUARANTINE|DAMAGED")
            assertThat(scalar("SELECT legal_owner FROM inventory_asset_assignment WHERE id='${returned.old.installation.operation}' AND ended_at IS NOT NULL"))
                .isEqualTo("CUSTOMER")
        }
        val missingReset = request("POST", "$path/inspect", admin,
            """{"expectedRevision":${current.path("revision").asLong()},"measuredQuantityBase":"1","condition":"SERVICEABLE","destinationLocationId":"${receipt.stock.bin}","evidenceReference":"reacquired-device-inspection","observedSerial":"${receipt.input.lines.single().serial}","resetConfirmed":false}""", "returned-title-no-reset")
        assertThat(missingReset.status).withFailMessage(missingReset.contentAsString).isEqualTo(409)
        val inspected = request("POST", "$path/inspect", admin,
            """{"expectedRevision":${current.path("revision").asLong()},"measuredQuantityBase":"1","condition":"SERVICEABLE","destinationLocationId":"${receipt.stock.bin}","evidenceReference":"reacquired-device-inspection","observedSerial":"${receipt.input.lines.single().serial}","resetConfirmed":true,"resetEvidenceReference":"customer-data-erased"}""", "returned-title-inspection")
        assertThat(inspected.status).withFailMessage(inspected.contentAsString).isEqualTo(200)
        val beforeReplay = fixture(admin).transaction { scalar("SELECT count(*) FROM inventory_movement") }
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", checker.first, decision, "returned-title-decision").contentAsString)
            .isEqualTo(approved.contentAsString)
        assertThat(request("POST", "$path/reacquisition", admin, body, "returned-title-request").contentAsString).isEqualTo(correction.contentAsString)
        val conflicting = request("POST", "$path/reacquisition", admin, body.replace("signed-customer-reacquisition", "different-proof"), "returned-title-request")
        assertThat(conflicting.status).withFailMessage(conflicting.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(conflicting.contentAsString).path("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT")
        val history = request("GET", "$path/history", admin)
        assertThat(history.status).withFailMessage(history.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(history.contentAsString).asSequence().map { it.path("legalOwner").asString() }.toList())
            .containsExactly("CUSTOMER", "CUSTOMER", "CUSTOMER", "ISP", "ISP")
        fixture(admin).transaction {
            context.getBean(com.duluin.ftth.inventory.application.port.outbound.WarehousePosting::class.java).rebuild(0)
        }
        fixture(admin).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(beforeReplay)
            assertThat(scalar("SELECT concat_ws('|',legal_owner,status,condition) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("ISP|AVAILABLE|SERVICEABLE")
        }
    }
}
