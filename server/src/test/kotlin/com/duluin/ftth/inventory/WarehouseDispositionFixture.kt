package com.duluin.ftth.inventory

import com.duluin.ftth.fulfillment.MaterialLifecycleFixture
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

abstract class WarehouseDispositionFixture : MaterialLifecycleFixture() {
    protected var receiptCost: String? = "1000000"
    protected var receiptCurrency = "IDR"
    override fun stockReceiptCost(): Map<String, String>? = receiptCost?.let { mapOf("totalMinor" to it, "currency" to receiptCurrency) }

    protected data class DispositionCase(val residual: ResidualCase, val returnId: String, val identity: String,
        val sink: String, val checker: Pair<String, String>, val input: WarehouseDispositionInput) {
        val token: String get() = residual.usage.receipt.stock.token
    }

    protected fun dispositionCase(action: WarehouseDispositionAction = WarehouseDispositionAction.SCRAP,
        condition: String = "DAMAGED"): DispositionCase {
        val residualCase = residualCase()
        val receipt = residualCase.usage.receipt
        val admin = receipt.stock.token
        val residual = dispatchedResidual(residualCase)
        assertThat(acknowledgeResidual(residualCase, residual).status).isEqualTo(200)
        val intake = request("POST", "/api/v1/warehouse/returns", admin,
            """{"origin":"MATERIAL_RESIDUAL","sourceDocumentId":"$residual","quarantineLocationId":"${residualCase.input.targetLocationId}","evidenceReference":"damaged-remnant-intake"}""")
        assertThat(intake.status).withFailMessage(intake.contentAsString).isEqualTo(201)
        val returned = mapper.readTree(intake.contentAsString)
        val returnId = returned.path("id").asString()
        val identity = returned.path("stockIdentityId").asString()
        val inspected = request("POST", "/api/v1/warehouse/returns/$returnId/inspect", admin,
            """{"expectedRevision":0,"measuredQuantityBase":"17500","condition":"$condition","destinationLocationId":"${residualCase.input.targetLocationId}","evidenceReference":"damaged-remnant-inspection","resetConfirmed":false}""")
        assertThat(inspected.status).withFailMessage(inspected.contentAsString).isEqualTo(200)
        val sink = create("locations", admin, """{"code":"${action}_SINK","name":"Approved scrap","kind":"${if (action == WarehouseDispositionAction.LOSS) "LOST" else "DISPOSED"}"}""").path("id").asString()
        val checker = user(admin, setOf("inventory.approval.view", "inventory.approval.decide"))
        val principal = mapper.readTree(request("GET", "/api/users/${checker.second}", admin).contentAsString)
        assertThat(request("PUT", "/api/users/${checker.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        for (location in listOf(residualCase.input.targetLocationId.toString(), sink)) {
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${checker.second}/$location", admin,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        }
        val policy = request("PUT", "/api/v1/warehouse/settings/policy", admin,
            """{"expectedRevision":0,"currency":"IDR","expiryHours":24,"warehouseIds":["${residualCase.input.targetLocationId}","$sink"],"rules":[{"operation":"$action","tiers":[{"minimumMinor":"1","userIds":["${checker.second}"],"roleIds":[]}]}]}""")
        assertThat(policy.status).withFailMessage(policy.contentAsString).isEqualTo(200)

        return DispositionCase(residualCase, returnId, identity, sink, checker, WarehouseDispositionInput(UUID.fromString(returnId), 1,
            UUID.fromString(identity), "17500", WarehouseBaseUnit.MM, UUID.fromString(sink), action,
            "Measured remnant requires independently authorized disposition", "signed-disposition-assessment"))
    }

    protected fun disposition(case: DispositionCase, key: String = "disposition-request", input: WarehouseDispositionInput = case.input,
        token: String = case.token): String {
        val result = request("POST", "/api/v1/warehouse/dispositions", token, mapper.writeValueAsString(input), key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
        return mapper.readTree(result.contentAsString).path("id").asString()
    }

    protected fun dispositionApproval(case: DispositionCase, document: String, key: String = "disposition-approval"): String {
        val result = request("POST", "/api/v1/warehouse/approvals/request", case.token,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
        return mapper.readTree(result.contentAsString).path("requestId").asString()
    }

    protected fun dispositionDecision(case: DispositionCase, approval: String, key: String = "disposition-decision",
        token: String = case.checker.first, action: String = "APPROVE") =
        request("POST", "/api/v1/warehouse/approvals/decide", token,
            """{"requestId":"$approval","expectedRevision":0,"decision":"$action","reason":"Independent evidence reviewed"}""", key)
}
