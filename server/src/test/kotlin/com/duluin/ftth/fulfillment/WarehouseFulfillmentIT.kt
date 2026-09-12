package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.MaterialMode
import com.duluin.ftth.inventory.MaterialUsageRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.util.UUID

class WarehouseFulfillmentIT : WarehouseFulfillmentFixture() {
    @Autowired private lateinit var coordinator: FulfillmentCoordinator

    @Test
    fun `approval verifies existing cable usage when the submitted job has no service links`() {
        val case = usageCase()
        used(case)
        completeJob(case.receipt.workOrder, case.receipt.receiver.first)
        val before = physicalState(case.receipt.stock.token)

        val response = request("POST", "/api/work-orders/${case.receipt.workOrder}/approve", case.receipt.stock.token, "{}")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(physicalState(case.receipt.stock.token)).isEqualTo(before)
        fixture(case.receipt.stock.token).transaction {
            assertThat(scalar("SELECT state FROM fulfillment_checkpoint WHERE work_order_id='${case.receipt.workOrder}'"))
                .isEqualTo("APPLIED")
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress WHERE status='COMPLETED' AND effect_type='INVENTORY'"))
                .isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress WHERE effect_type IN ('SUBSCRIPTION','PROVISIONING','ORDER','VISIT')"))
                .isEqualTo("0")
        }
    }

    @Test
    fun `approval conflicts before lifecycle changes when required material usage is missing`() {
        val case = usageCase()
        completeJob(case.receipt.workOrder, case.receipt.receiver.first)
        val before = physicalState(case.receipt.stock.token)

        val response = request("POST", "/api/work-orders/${case.receipt.workOrder}/approve", case.receipt.stock.token, "{}")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        assertThat(physicalState(case.receipt.stock.token)).isEqualTo(before)
        fixture(case.receipt.stock.token).transaction {
            assertThat(scalar("SELECT approval_status FROM work_order WHERE id='${case.receipt.workOrder}'")).isEqualTo("PENDING")
            assertThat(scalar("SELECT count(*) FROM fulfillment_checkpoint")).isEqualTo("0")
        }
    }

    @Test
    fun `standalone approval applies only material verification and WO effects when NONE is explicitly submitted`() {
        val stock = setupReceipt()
        val receiver = technician(stock.token)
        val workOrder = workOrder(stock.token)
        assign(stock.token, workOrder, receiver.second)
        putPlan(stock.token, workOrder, plan(stock.token, workOrder, "[]", mode = "NONE", reason = "Inspection only"))
        action(stock.token, workOrder, "submit-request", command(stock.token, workOrder, 1))
        val revision = summary(receiver.first, workOrder).path("revisions").path("workOrderRevision").asLong()
        val input = MaterialUsageRequest(0, 1, revision, MaterialMode.NONE, "inspection-log", emptyList(), "Inspection only")
        assertThat(request("POST", "/api/work-orders/$workOrder/materials/report-use", receiver.first, mapper.writeValueAsString(input)).status)
            .isEqualTo(200)
        completeJob(workOrder, receiver.first)

        val response = request("POST", "/api/work-orders/$workOrder/approve", stock.token, "{}")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        fixture(stock.token).transaction {
            assertThat(scalar("SELECT state FROM fulfillment_checkpoint WHERE work_order_id='$workOrder'")).isEqualTo("APPLIED")
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress WHERE status='COMPLETED'")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_customer_material_fact")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress WHERE effect_type IN ('SUBSCRIPTION','PROVISIONING','ORDER','VISIT')"))
                .isEqualTo("0")
        }
    }

    @Test
    fun `ambiguous legacy delivery requires durable reconciliation when applicability is absent`() {
        val token = tenant()
        val tenant = fixture(token).tenant
        val input = FulfillmentRequest(tenant, "workorder.fulfillment.approve", UUID.randomUUID().toString(), "a".repeat(64),
            FulfillmentSource.WORK_ORDER, UUID.randomUUID(), null, null, "PREVENTIVE", true, approvalActorId = UUID.randomUUID())
        TenantContext.runAs(tenant) { coordinator.accept(input) }

        val result = TenantContext.runAs(tenant) { coordinator.process(input) }

        assertThat(result.state).isEqualTo(FulfillmentState.REQUIRES_RECONCILIATION)
        fixture(token).transaction {
            assertThat(scalar("SELECT state FROM fulfillment_checkpoint WHERE operation_key='${input.operationKey}'"))
                .isEqualTo("REQUIRES_RECONCILIATION")
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress")).isEqualTo("0")
        }
    }

}
