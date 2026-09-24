package com.duluin.ftth.inventory

import com.duluin.ftth.customer.CustomerAssetReplacementFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import java.util.Base64
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart

class WarehouseAssetLossIT : CustomerAssetReplacementFixture() {
    override fun stockReceiptCost() = mapOf("totalMinor" to "200000", "currency" to "IDR")

    @ParameterizedTest
    @ValueSource(strings = ["LOAN", "SALE"])
    fun `only independent approved ISP loan loss retires the real installation without inventing a return`(mode: String) {
        val old = ownershipCase(mode)
        val receipt = old.installation.receipt
        val admin = receipt.stock.token
        val asset = receipt.input.lines.single().stockIdentityId
        assertThat(accept(old).status).isEqualTo(200)
        val handover = fixture(admin).transaction {
            scalar("SELECT id FROM inventory_asset_handover WHERE assignment_id='${old.installation.operation}'")
        }
        val source = fixture(admin).transaction { scalar("SELECT location_id FROM inventory_serialized_asset WHERE id='$asset'") }
        val sink = create("locations", admin, """{"code":"LOAN_LOSS","name":"Approved lost loans","kind":"LOST"}""").path("id").asString()
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jK1cAAAAASUVORK5CYII=")
        val signed = mvc.perform(multipart(HttpMethod.PUT, "/api/work-orders/${receipt.workOrder}/signature")
            .file(MockMultipartFile("file", "loss-assessment.png", "image/png", png))
            .param("signerName", "Loan loss assessment")
            .param("correctionReason", "Record a later witnessed loss assessment and preserve the original acceptance")
            .header("Authorization", "Bearer ${receipt.receiver.first}")).andReturn().response
        assertThat(signed.status).withFailMessage(signed.contentAsString).isEqualTo(200)
        val evidence = UUID.fromString(mapper.readTree(signed.contentAsString).path("revisionId").asString())
        val input = WarehouseAssetLossInput(old.installation.operation, UUID.fromString(handover), 1,
            if (mode == "SALE") 1 else 0, fixture(admin).transaction { scalar("SELECT warehouse_revision FROM work_order WHERE id='${receipt.workOrder}'").toLong() },
            UUID.fromString(sink), "Confirmed unrecovered device loss with independent assessment", evidence)
        val body = mapper.writeValueAsString(input)
        val created = request("POST", "/api/v1/warehouse/asset-losses", admin, body, "loan-loss-request")
        if (mode == "SALE") {
            assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(409)
            fixture(admin).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_asset_loss_request")).isEqualTo("0")
                assertThat(scalar("SELECT legal_owner FROM inventory_serialized_asset WHERE id='$asset'")).isEqualTo("CUSTOMER")
                assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE id='${old.installation.operation}' AND ended_at IS NULL")).isEqualTo("1")
            }
            return
        }
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
        assertThat(request("POST", "/api/v1/warehouse/asset-losses", admin, body, "loan-loss-request").contentAsString).isEqualTo(created.contentAsString)
        val document = mapper.readTree(created.contentAsString).path("id").asString()
        assertThat(request("GET", "/api/v1/warehouse/asset-losses/$document", admin).status).isEqualTo(200)
        val listed = request("GET", "/api/v1/warehouse/asset-losses?size=1", admin)
        assertThat(listed.status).withFailMessage(listed.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(listed.contentAsString).path("totalElements").asLong()).isEqualTo(1)
        fixture(admin).transaction {
            assertThat(scalar("SELECT status FROM inventory_serialized_asset WHERE id='$asset'")).isEqualTo("CUSTOMER_INSTALLED")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE id='${old.installation.operation}' AND ended_at IS NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='LOSS'")).isEqualTo("0")
        }
        val checker = user(admin, setOf("inventory.approval.view", "inventory.approval.decide"))
        val principal = mapper.readTree(request("GET", "/api/users/${checker.second}", admin).contentAsString)
        assertThat(request("PUT", "/api/users/${checker.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        for (location in listOf(source, sink)) {
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${checker.second}/$location", admin,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        }
        val policy = request("PUT", "/api/v1/warehouse/settings/policy", admin,
            """{"expectedRevision":0,"currency":"IDR","expiryHours":24,"warehouseIds":["$source","$sink"],"rules":[{"operation":"LOSS","tiers":[{"minimumMinor":"1","userIds":["${checker.second}"],"roleIds":[]}]}]}""")
        assertThat(policy.status).withFailMessage(policy.contentAsString).isEqualTo(200)
        val approval = request("POST", "/api/v1/warehouse/approvals/request", admin,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", "loan-loss-approval")
        assertThat(approval.status).withFailMessage(approval.contentAsString).isEqualTo(201)
        val decision = """{"requestId":"${mapper.readTree(approval.contentAsString).path("requestId").asString()}","expectedRevision":0,"decision":"APPROVE","reason":"Independent loan and loss evidence verified"}"""
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", admin, decision, "loan-loss-self").status).isEqualTo(403)
        val approved = request("POST", "/api/v1/warehouse/approvals/decide", checker.first, decision, "loan-loss-decide")
        assertThat(approved.status).withFailMessage(approved.contentAsString).isEqualTo(200)
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", checker.first, decision, "loan-loss-decide").contentAsString).isEqualTo(approved.contentAsString)
        assertThat(request("POST", "/api/v1/warehouse/asset-losses", admin, body, "loan-loss-request").contentAsString).isEqualTo(created.contentAsString)
        fixture(admin).transaction {
            assertThat(scalar("SELECT concat_ws('|',status,legal_owner,custody_owner_kind) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("LOST|ISP|LOST")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE id='${old.installation.operation}' AND ended_at IS NOT NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM onu WHERE assignment_id='${old.installation.operation}' AND retired_at IS NOT NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='LOSS'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_removal")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_return_case")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_recovery_obligation WHERE assignment_id='${old.installation.operation}'")).isEqualTo("1")
        }
    }
}
