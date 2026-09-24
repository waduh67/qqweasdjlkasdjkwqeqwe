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
        val approval = request("POST", "/api/v1/warehouse/approvals/request", admin,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", "returned-title-approval")
        assertThat(approval.status).withFailMessage(approval.contentAsString).isEqualTo(201)
        val approvalId = mapper.readTree(approval.contentAsString).path("requestId").asString()
        val decision = """{"requestId":"$approvalId","expectedRevision":0,"decision":"APPROVE","reason":"Independent title proof verified"}"""
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", admin, decision, "returned-self-approval").status).isIn(403, 409)
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
        fixture(admin).transaction {
            assertThat(scalar("SELECT concat_ws('|',legal_owner,status,condition) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("ISP|QUARANTINE|DAMAGED")
            assertThat(scalar("SELECT legal_owner FROM inventory_asset_assignment WHERE id='${returned.old.installation.operation}' AND ended_at IS NOT NULL"))
                .isEqualTo("CUSTOMER")
        }
        val inspected = request("POST", "$path/inspect", admin,
            """{"expectedRevision":${current.path("revision").asLong()},"measuredQuantityBase":"1","condition":"SERVICEABLE","destinationLocationId":"${receipt.stock.bin}","evidenceReference":"reacquired-device-inspection","observedSerial":"${receipt.input.lines.single().serial}","resetConfirmed":true,"resetEvidenceReference":"customer-data-erased"}""", "returned-title-inspection")
        assertThat(inspected.status).withFailMessage(inspected.contentAsString).isEqualTo(200)
        val beforeReplay = fixture(admin).transaction { scalar("SELECT count(*) FROM inventory_movement") }
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", checker.first, decision, "returned-title-decision").contentAsString)
            .isEqualTo(approved.contentAsString)
        fixture(admin).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(beforeReplay)
            assertThat(scalar("SELECT concat_ws('|',legal_owner,status,condition) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("ISP|AVAILABLE|SERVICEABLE")
        }
    }
}
