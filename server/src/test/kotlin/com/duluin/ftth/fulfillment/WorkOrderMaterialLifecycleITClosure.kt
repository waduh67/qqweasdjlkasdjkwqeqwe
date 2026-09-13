package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkOrderMaterialLifecycleITClosure : MaterialUsageFixture() {
    @Test fun `material closes independently when all acknowledged stock has been consumed`() {
        val case = usageCase()
        val consumed = use(case, case.input.copy(lines = case.input.lines.map { it.copy(quantityBase = "100000") }))
        assertThat(consumed.status).withFailMessage(consumed.contentAsString).isEqualTo(200)
        val before = usageAccounting(case)

        val response = request("POST", "/api/work-orders/${case.receipt.workOrder}/materials/settlement", case.receipt.stock.token,
            """{"expectedRevision":0,"workOrderRevision":${case.input.workOrderRevision},"reason":"No physical residual remains"}""", "material-close")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(response.contentAsString).path("materialState").asString()).isEqualTo("CLOSED")
        assertThat(usageAccounting(case)).isEqualTo(before)
    }
}
