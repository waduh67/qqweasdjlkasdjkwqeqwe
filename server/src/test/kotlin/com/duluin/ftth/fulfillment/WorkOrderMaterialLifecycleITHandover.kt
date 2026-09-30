package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WorkOrderMaterialLifecycleITHandover : MaterialLifecycleFixture() {
    @Test fun `partial handover needs dispatcher sender and named receiver before custody changes`() {
        val case = residualCase("7500")
        val admin = case.usage.receipt.stock.token
        val receiver = technician(admin)
        assign(admin, case.usage.receipt.workOrder, receiver.second)
        val target = create("locations", admin,
            """{"code":"HANDOVER_RECEIVER","name":"Receiver field custody","kind":"TECHNICIAN","custodianId":"${receiver.second}"}""").path("id").asString()
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${receiver.second}/$target", admin,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val revision = summary(admin, case.usage.receipt.workOrder).path("revisions").path("workOrderRevision").asLong()
        val input = case.input.copy(workOrderRevision = revision, targetLocationId = UUID.fromString(target))
        val authorized = request("POST", "/api/work-orders/${case.usage.receipt.workOrder}/materials/handover/authorize", admin,
            mapper.writeValueAsString(input), "handover-authorize")
        assertThat(authorized.status).withFailMessage(authorized.contentAsString).isEqualTo(200)
        val authorization = UUID.fromString(mapper.readTree(authorized.contentAsString).path("authorizationId").asString())
        val dispatched = request("POST", "/api/work-orders/${case.usage.receipt.workOrder}/materials/handover", case.usage.receipt.receiver.first,
            mapper.writeValueAsString(input.copy(authorizationId = authorization)), "handover-dispatch")
        assertThat(dispatched.status).withFailMessage(dispatched.contentAsString).isEqualTo(200)
        val id = mapper.readTree(dispatched.contentAsString).path("id").asString()
        fixture(admin).transaction {
            assertThat(scalar("SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE custody_owner_id='${receiver.second}'")).isEqualTo("0")
        }

        val accepted = acknowledgeResidual(case, id, receiver.first)

        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        fixture(admin).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='ISSUED' AND custody_owner_id='${receiver.second}'")).isEqualTo("7500")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='ISSUED' AND custody_owner_id='${case.usage.receipt.receiver.second}'")).isEqualTo("10000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='CONSUMED'")).isEqualTo("82500")
        }
    }
}
