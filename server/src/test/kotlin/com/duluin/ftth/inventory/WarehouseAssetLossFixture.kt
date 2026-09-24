package com.duluin.ftth.inventory

import com.duluin.ftth.customer.CustomerAssetReplacementFixture
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.util.Base64
import java.util.UUID

abstract class WarehouseAssetLossFixture : CustomerAssetReplacementFixture() {
    protected var lossCost: String? = "200000"
    override fun stockReceiptCost(): Map<String, String>? = lossCost?.let { mapOf("totalMinor" to it, "currency" to "IDR") }
    protected data class LossCase(val old: OwnershipCase, val source: String, val sink: String,
        val input: WarehouseAssetLossInput, val checker: Pair<String, String>) {
        val token: String get() = old.installation.receipt.stock.token
        val asset: UUID get() = old.installation.receipt.input.lines.single().stockIdentityId
    }

    protected fun lossCase(old: OwnershipCase = ownershipCase("LOAN")): LossCase {
        val receipt = old.installation.receipt
        val admin = receipt.stock.token
        assertThat(accept(old).status).isEqualTo(200)
        val handover = fixture(admin).transaction { scalar("SELECT id FROM inventory_asset_handover WHERE assignment_id='${old.installation.operation}'") }
        val source = fixture(admin).transaction { scalar("SELECT location_id FROM inventory_serialized_asset WHERE id='${receipt.input.lines.single().stockIdentityId}'") }
        val sink = create("locations", admin, """{"code":"LOAN_LOSS","name":"Approved lost loans","kind":"LOST"}""").path("id").asString()
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jK1cAAAAASUVORK5CYII=")
        val signed = mvc.perform(multipart(HttpMethod.PUT, "/api/work-orders/${receipt.workOrder}/signature")
            .file(MockMultipartFile("file", "loss-assessment.png", "image/png", png))
            .param("signerName", "Loan loss assessment")
            .param("correctionReason", "Record a later witnessed loss assessment and preserve the original acceptance")
            .header("Authorization", "Bearer ${receipt.receiver.first}")).andReturn().response
        assertThat(signed.status).withFailMessage(signed.contentAsString).isEqualTo(200)
        val evidence = UUID.fromString(mapper.readTree(signed.contentAsString).path("revisionId").asString())
        val revision = fixture(admin).transaction { scalar("SELECT warehouse_revision FROM work_order WHERE id='${receipt.workOrder}'").toLong() }
        val input = WarehouseAssetLossInput(old.installation.operation, UUID.fromString(handover), 1, 0, revision,
            UUID.fromString(sink), "Unrecovered loan requires independent loss assessment", evidence)
        val checker = user(admin, setOf("inventory.approval.view", "inventory.approval.decide"))
        grantLossLocations(admin, checker, listOf(source, sink))
        val policy = request("PUT", "/api/v1/warehouse/settings/policy", admin,
            """{"expectedRevision":0,"currency":"IDR","expiryHours":24,"warehouseIds":["$source","$sink"],"rules":[{"operation":"LOSS","tiers":[{"minimumMinor":"1","userIds":["${checker.second}"],"roleIds":[]}]}]}""")
        assertThat(policy.status).withFailMessage(policy.contentAsString).isEqualTo(200)
        return LossCase(old, source, sink, input, checker)
    }

    protected fun grantLossLocations(admin: String, user: Pair<String, String>, locations: List<String>) {
        val principal = mapper.readTree(request("GET", "/api/users/${user.second}", admin).contentAsString)
        assertThat(request("PUT", "/api/users/${user.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        for (location in locations) assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${user.second}/$location", admin,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
    }

    protected fun lossRequest(case: LossCase, key: String = "loss-request", input: WarehouseAssetLossInput = case.input): String {
        val result = request("POST", "/api/v1/warehouse/asset-losses", case.token, mapper.writeValueAsString(input), key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
        return mapper.readTree(result.contentAsString).path("id").asString()
    }

    protected fun lossApproval(case: LossCase, document: String, key: String = "loss-approval"): String {
        val result = request("POST", "/api/v1/warehouse/approvals/request", case.token,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
        return mapper.readTree(result.contentAsString).path("requestId").asString()
    }

    protected fun lossDecision(case: LossCase, approval: String, key: String = "loss-decision", action: String = "APPROVE") =
        request("POST", "/api/v1/warehouse/approvals/decide", case.checker.first,
            """{"requestId":"$approval","expectedRevision":0,"decision":"$action","reason":"Independent source and loss assessment reviewed"}""", key)
}
