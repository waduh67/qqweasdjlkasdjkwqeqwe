package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.WarehousePosting
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class WarehouseDispositionAssetIT : WarehouseReturnAssetFixture() {
    override fun stockReceiptCost(): Map<String, String> = mapOf("totalMinor" to "200000", "currency" to "IDR")

    @ParameterizedTest
    @CsvSource("LOAN,SCRAP", "LOAN,LOSS", "SALE,SCRAP", "SALE,LOSS")
    fun `recovered ISP device disposition preserves history while customer title blocks writeoff`(mode: String, action: String) {
        val returned = recoveredReturn(mode)
        val receipt = returned.old.installation.receipt
        val token = receipt.stock.token
        val asset = receipt.input.lines.single().stockIdentityId
        val status = if (action == "LOSS") "LOST" else "DISPOSED"
        val condition = if (action == "LOSS") "DAMAGED" else "SCRAP"
        val sink = create("locations", token, """{"code":"ASSET_DISPOSITION","name":"Approved device disposition","kind":"$status"}""")
            .path("id").asString()
        val draft = request("POST", "/api/v1/warehouse/dispositions", token,
            """{"sourceDocumentId":"${returned.id}","expectedRevision":${returned.revision},"stockIdentityId":"$asset","quantityBase":"1","baseUnit":"EA","destinationLocationId":"$sink","action":"$action","reason":"Independently assessed device disposition","evidenceReference":"device-serial-assessment"}""", "asset-disposition")
        if (mode == "SALE") {
            assertThat(draft.status).withFailMessage(draft.contentAsString).isEqualTo(409)
            fixture(token).transaction {
                assertThat(scalar("SELECT concat_ws('|',status,condition,legal_owner) FROM inventory_serialized_asset WHERE id='$asset'"))
                    .isEqualTo("QUARANTINE|DAMAGED|CUSTOMER")
                assertThat(scalar("SELECT count(*) FROM inventory_disposition_request")).isEqualTo("0")
            }
            return
        }
        assertThat(draft.status).withFailMessage(draft.contentAsString).isEqualTo(201)
        val document = mapper.readTree(draft.contentAsString).path("id").asString()
        val checker = user(token, setOf("inventory.approval.view", "inventory.approval.decide"))
        val principal = mapper.readTree(request("GET", "/api/users/${checker.second}", token).contentAsString)
        assertThat(request("PUT", "/api/users/${checker.second}/access", token, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(token))))).status).isEqualTo(200)
        for (location in listOf(returned.quarantine, sink)) {
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${checker.second}/$location", token,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        }
        val policy = request("PUT", "/api/v1/warehouse/settings/policy", token,
            """{"expectedRevision":0,"currency":"IDR","expiryHours":24,"warehouseIds":["${returned.quarantine}","$sink"],"rules":[{"operation":"$action","tiers":[{"minimumMinor":"1","userIds":["${checker.second}"],"roleIds":[]}]}]}""")
        assertThat(policy.status).withFailMessage(policy.contentAsString).isEqualTo(200)
        val approval = request("POST", "/api/v1/warehouse/approvals/request", token,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", "asset-disposition-approval")
        assertThat(approval.status).withFailMessage(approval.contentAsString).isEqualTo(201)
        val decision = """{"requestId":"${mapper.readTree(approval.contentAsString).path("requestId").asString()}","expectedRevision":0,"decision":"APPROVE","reason":"Independent asset evidence reviewed"}"""
        val approved = request("POST", "/api/v1/warehouse/approvals/decide", checker.first, decision, "asset-disposition-decision")
        assertThat(approved.status).withFailMessage(approved.contentAsString).isEqualTo(200)
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", checker.first, decision, "asset-disposition-decision").contentAsString)
            .isEqualTo(approved.contentAsString)
        val scoped = fixture(token)
        val before = scoped.transaction { scalar("SELECT count(*) FROM inventory_movement") }
        scoped.transaction {
            sql("DELETE FROM inventory_balance_projection WHERE stock_identity_id='$asset'")
            context.getBean(WarehousePosting::class.java).rebuild(0)
        }
        scoped.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(before)
            assertThat(scalar("SELECT concat_ws('|',status,condition,legal_owner) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("$status|$condition|ISP")
            assertThat(scalar("SELECT concat_ws('|',quantity_base,status,condition,legal_owner) FROM inventory_balance_projection WHERE stock_identity_id='$asset' AND quantity_base>0"))
                .isEqualTo("1|$status|$condition|ISP")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='$asset' AND ended_at IS NOT NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='$asset' AND ended_at IS NULL")).isEqualTo("0")
        }
    }
}
