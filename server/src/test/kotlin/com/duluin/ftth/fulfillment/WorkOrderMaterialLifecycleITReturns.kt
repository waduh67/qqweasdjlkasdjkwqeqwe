package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkOrderMaterialLifecycleITReturns : MaterialLifecycleFixture() {
    @Test fun `old custodian cannot read private settlement after source scope revocation`() {
        val case = residualCase()
        val receipt = case.usage.receipt
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${receipt.receiver.second}/${receipt.field}", receipt.stock.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)

        val response = request("GET", "/api/work-orders/${receipt.workOrder}/materials/settlement", receipt.receiver.first)

        assertThat(response.status).isEqualTo(404)
    }

    @Test fun `receiver acknowledgement keeps returned material quarantined and closure blocked`() {
        val case = residualCase()
        val id = dispatchedResidual(case)

        val result = acknowledgeResidual(case, id)

        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        fixture(case.usage.receipt.stock.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='CONSUMED'")).isEqualTo("82500")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='QUARANTINE'")).isEqualTo("17500")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("900000")
        }
        val state = settlement(case)
        assertThat(state.status).withFailMessage(state.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(state.contentAsString).path("outstandingBase").asString()).isEqualTo("17500")
        assertThat(mapper.readTree(state.contentAsString).path("materialState").asString()).isEqualTo("SETTLING")
        assertThat(request("GET", "/api/work-orders/${case.usage.receipt.workOrder}/materials", case.usage.receipt.stock.token).status).isEqualTo(200)
        assertThat(summary(case.usage.receipt.stock.token, case.usage.receipt.workOrder).path("settlementState").asString()).isEqualTo("OPEN")
    }

    @Test fun `partial return retains the sender remainder`() {
        val case = residualCase("7500")

        val result = dispatchResidual(case)

        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        fixture(case.usage.receipt.stock.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='ISSUED'")).isEqualTo("10000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='IN_TRANSIT'")).isEqualTo("7500")
        }
    }

    @Test fun `return dispatch replay has exact bytes and no new physical posting`() {
        val case = residualCase()
        val first = dispatchResidual(case)
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(200)
        val before = usageAccounting(case.usage)

        val replay = dispatchResidual(case)

        assertThat(replay.status).withFailMessage(replay.contentAsString).isEqualTo(200)
        assertThat(replay.contentAsString).isEqualTo(first.contentAsString)
        assertThat(usageAccounting(case.usage)).isEqualTo(before)
    }

    @Test fun `acknowledgement replay has no second custody transfer`() {
        val case = residualCase()
        val id = dispatchedResidual(case)
        val first = acknowledgeResidual(case, id)
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(200)
        val before = usageAccounting(case.usage)

        val replay = acknowledgeResidual(case, id)

        assertThat(replay.status).isEqualTo(200)
        assertThat(replay.contentAsString).isEqualTo(first.contentAsString)
        assertThat(usageAccounting(case.usage)).isEqualTo(before)
    }

    @Test fun `replacement assignee cannot return old custodians identity`() {
        val case = residualCase()
        val replacement = technician(case.usage.receipt.stock.token)
        assign(case.usage.receipt.stock.token, case.usage.receipt.workOrder, replacement.second)
        val revision = summary(case.usage.receipt.stock.token, case.usage.receipt.workOrder).path("revisions").path("workOrderRevision").asLong()
        val before = usageAccounting(case.usage)

        val response = request("POST", "/api/work-orders/${case.usage.receipt.workOrder}/materials/return", replacement.first,
            mapper.writeValueAsString(case.input.copy(workOrderRevision = revision)), "steal-return")

        assertThat(response.status).isIn(403, 409)
        assertThat(usageAccounting(case.usage)).isEqualTo(before)
    }
}
