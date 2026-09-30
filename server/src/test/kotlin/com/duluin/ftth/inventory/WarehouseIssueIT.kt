package com.duluin.ftth.inventory

import com.duluin.ftth.fulfillment.MaterialWorkflowFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseIssueIT : MaterialWorkflowFixture() {
    @Test fun `pick a reserved cable cut through owner APIs without transferring custody`() {
        val setup = setupReceipt()
        receiveStock(setup, "1000000")
        val technician = technician(setup.token)
        val workOrder = workOrder(setup.token)
        assign(setup.token, workOrder, technician.second)
        putPlan(setup.token, workOrder, plan(setup.token, workOrder,
            "[{\"skuId\":\"${setup.cable}\",\"quantityBase\":\"100000\",\"baseUnit\":\"MM\"}]"))
        action(setup.token, workOrder, "submit-request", command(setup.token, workOrder, 1))
        action(setup.token, workOrder, "reserve", command(setup.token, workOrder, 1))
        val summary = summary(setup.token, workOrder)
        val allocations = request("GET", "/api/v1/warehouse/material-requests/allocations/$workOrder", setup.token)
        assertThat(allocations.status).withFailMessage(allocations.contentAsString).isEqualTo(200)
        val allocation = mapper.readTree(allocations.contentAsString)[0]
        val body = mapper.writeValueAsString(mapOf(
            "expectedRevision" to 1, "workOrderRevision" to summary.path("revisions").path("workOrderRevision").asLong(),
            "demandRevision" to summary.path("demandRevision").asLong(),
            "lines" to listOf(mapOf("reservationId" to allocation.path("reservationId").asString(),
                "expectedRevision" to allocation.path("reservationRevision").asLong(),
                "stockIdentityId" to allocation.path("stockIdentityId").asString(),
                "stockRevision" to allocation.path("stockRevision").asLong(), "quantityBase" to "100000", "baseUnit" to "MM"))))
        val picked = request("POST", "/api/work-orders/$workOrder/materials/pick", setup.token, body, "pick-cable")
        assertThat(picked.status).withFailMessage(picked.contentAsString).isEqualTo(200)
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_segment WHERE state='SPLIT'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_segment WHERE state='ACTIVE' AND quantity_base IN (100000,900000)")).isEqualTo("2")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE base_unit='MM'")).isEqualTo("1000000")
            assertThat(scalar("SELECT sum(reserved_picked_base) FROM inventory_reservation WHERE state='OPEN'")).isEqualTo("100000")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE quantity_base>0 AND custody_owner_kind='TECHNICIAN'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_reservation")).isEqualTo("1")
        }
        val first = mapper.readTree(picked.contentAsString)
        assertThat(request("POST", "/api/work-orders/$workOrder/materials/pick", setup.token, body, "pick-cable").contentAsString).isEqualTo(picked.contentAsString)
        assertThat(request("POST", "/api/work-orders/$workOrder/materials/pick", setup.token, body, "new-pick-key").status).isEqualTo(409)
        val afterPick = summary(setup.token, workOrder)
        assertThat(afterPick.path("lines")[0].path("reservedPickedBase").asString()).isEqualTo("100000")
        val unpickBody = issueBody(setup.token, workOrder, first.path("issueId").asString())
        action(setup.token, workOrder, "unpick", unpickBody)
        assertThat(summary(setup.token, workOrder).path("lines")[0].path("reservedUnpickedBase").asString()).isEqualTo("100000")
        val repick = mapper.readTree(body) as tools.jackson.databind.node.ObjectNode
        repick.put("demandRevision", summary(setup.token, workOrder).path("demandRevision").asLong())
        val selection = repick.path("lines")[0] as tools.jackson.databind.node.ObjectNode
        selection.put("expectedRevision", 2)
        selection.put("stockIdentityId", first.path("lines")[0].path("dimension").path("stockIdentityId").asString())
        val second = action(setup.token, workOrder, "pick", mapper.writeValueAsString(repick), "repick")
        val transit = create("locations", setup.token, """{"code":"WO_TRANSIT","name":"WO transit","kind":"TRANSIT"}""").path("id").asString()
        val dispatchBody = issueBody(setup.token, workOrder, second.path("issueId").asString())
        val dispatched = request("POST", "/api/work-orders/$workOrder/materials/dispatch", setup.token, dispatchBody, "dispatch")
        assertThat(dispatched.status).withFailMessage(dispatched.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(dispatched.contentAsString).path("state").asString()).isEqualTo("DISPATCHED")
        assertThat(request("POST", "/api/work-orders/$workOrder/materials/dispatch", setup.token, dispatchBody, "dispatch").contentAsString).isEqualTo(dispatched.contentAsString)
        assertThat(request("GET", "/api/work-orders/$workOrder/materials/issues/${second.path("issueId").asString()}/slip", setup.token).contentAsString).isEqualTo(dispatched.contentAsString)
        val afterDispatch = summary(setup.token, workOrder)
        assertThat(afterDispatch.path("lines")[0].path("issuedBase").asString()).isEqualTo("100000")
        assertThat(afterDispatch.path("lines")[0].path("reservedPickedBase").asString()).isEqualTo("0")
        assertThat(afterDispatch.path("lines")[0].path("backorderBase").asString()).isEqualTo("0")
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE location_id='${setup.bin}'")).isEqualTo("900000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE location_id='$transit' AND status='IN_TRANSIT'")).isEqualTo("100000")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE quantity_base>0 AND custody_owner_kind='TECHNICIAN'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_reservation WHERE state='DISPATCHED'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='ISSUE'")).isEqualTo("1")
        }
    }

    private fun issueBody(token: String, workOrder: String, issue: String): String {
        val state = summary(token, workOrder)
        return mapper.writeValueAsString(mapOf("issueId" to issue, "expectedRevision" to 1,
            "workOrderRevision" to state.path("revisions").path("workOrderRevision").asLong(), "planRevision" to 1,
            "demandRevision" to state.path("demandRevision").asLong(), "reason" to "Warehouse handover"))
    }
}
