package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.MaterialResidualRequest
import com.duluin.ftth.inventory.WarehouseBaseUnit
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

abstract class MaterialLifecycleFixture : MaterialUsageFixture() {
    protected data class ResidualCase(val usage: UsageCase, val input: MaterialResidualRequest)

    protected fun residualCase(quantity: String = "17500"): ResidualCase {
        val case = usageCase()
        val usage = mapper.readTree(used(case))
        assign(case.receipt.stock.token, case.receipt.workOrder, technician(case.receipt.stock.token).second)
        val target = create("locations", case.receipt.stock.token,
            """{"code":"RETURN_QUARANTINE","name":"Return intake","kind":"QUARANTINE"}""").path("id").asString()
        val revision = summary(case.receipt.stock.token, case.receipt.workOrder).path("revisions").path("workOrderRevision").asLong()
        val source = case.input.lines.single()
        return ResidualCase(case, MaterialResidualRequest(revision, source.receiptId, source.issueLineId,
            UUID.fromString(usage.path("lines")[0].path("remainder").path("stockIdentityId").asString()), quantity,
            WarehouseBaseUnit.MM, UUID.fromString(target), "Return unused material", "signed-sender-document",
            UUID.fromString(usage.path("usageId").asString())))
    }

    protected fun dispatchResidual(case: ResidualCase, key: String = "residual-dispatch") =
        request("POST", "/api/work-orders/${case.usage.receipt.workOrder}/materials/return", case.usage.receipt.receiver.first,
            mapper.writeValueAsString(case.input), key)

    protected fun dispatchedResidual(case: ResidualCase): String {
        val result = dispatchResidual(case)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        return mapper.readTree(result.contentAsString).path("id").asString()
    }

    protected fun acknowledgeResidual(case: ResidualCase, id: String, token: String = case.usage.receipt.stock.token, key: String = "residual-ack") =
        request("POST", "/api/work-orders/${case.usage.receipt.workOrder}/materials/residuals/acknowledge", token,
            """{"documentId":"$id","expectedRevision":1,"evidenceReference":"signed-receiver-document"}""", key)

    protected fun settlement(case: ResidualCase) = request("GET", "/api/work-orders/${case.usage.receipt.workOrder}/materials/settlement", case.usage.receipt.stock.token)
}
