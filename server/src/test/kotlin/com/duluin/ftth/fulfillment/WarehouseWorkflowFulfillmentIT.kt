package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.application.service.InventoryTenantPolicyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseWorkflowFulfillmentIT : WarehouseFulfillmentFixture() {
    @Test fun `frozen fulfillment finishes once across the draining transition`() {
        val case = FulfillmentSqlProbe(context, FulfillmentSqlPhase.COMPLETED_EFFECT) {
            error("Interrupted before delivery commit")
        }.use { approvedCable() }
        val request = frozenRequest(case)
        val database = fixture(case.receipt.stock.token)
        val before = physicalState(case.receipt.stock.token)
        database.transaction { context.getBean(InventoryTenantPolicyService::class.java).beginDraining(0) }

        val result = TenantContext.runAs(request.tenantId) {
            context.getBean(FulfillmentCoordinator::class.java).process(request)
        }

        assertThat(result.state).isEqualTo(FulfillmentState.APPLIED)
        assertThat(physicalState(case.receipt.stock.token)).isEqualTo(before)
        database.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_material_settlement")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress WHERE status='COMPLETED'")).isEqualTo("2")
        }
        val replay = TenantContext.runAs(request.tenantId) {
            context.getBean(FulfillmentCoordinator::class.java).process(request)
        }
        assertThat(replay.replayed).isTrue()
        assertThat(physicalState(case.receipt.stock.token)).isEqualTo(before)
    }

    @Test fun `existing work finishes during draining while new work is rejected`() {
        val case = usageCase()
        used(case)
        val receipt = case.receipt
        fixture(receipt.stock.token).transaction {
            context.getBean(InventoryTenantPolicyService::class.java).beginDraining(0)
        }

        completeJob(receipt.workOrder, receipt.receiver.first)

        assertThat(request("POST", "/api/work-orders/${receipt.workOrder}/approve", receipt.stock.token, "{}").status).isEqualTo(200)
        assertThat(request("POST", "/api/work-orders", receipt.stock.token,
            """{"type":"REPAIR","title":"Rejected new work"}""").status).isEqualTo(409)
        assertThat(request("GET", "/api/work-orders/${receipt.workOrder}", receipt.stock.token).status).isEqualTo(200)
        fixture(receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM work_order")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_material_settlement")).isEqualTo("1")
        }
    }
}
