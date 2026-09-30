package com.duluin.ftth.inventory

import com.duluin.ftth.fulfillment.MaterialLifecycleFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseReturnSettlementIT : MaterialLifecycleFixture() {
    @Test fun `accepted residual inspection closes the return obligation without erasing returned quantity or posting stock twice`() {
        val case = residualCase()
        val receipt = case.usage.receipt
        val admin = receipt.stock.token
        val source = dispatchedResidual(case)
        assertThat(acknowledgeResidual(case, source).status).isEqualTo(200)
        val received = request("POST", "/api/v1/warehouse/returns", admin,
            """{"origin":"MATERIAL_RESIDUAL","sourceDocumentId":"$source","quarantineLocationId":"${case.input.targetLocationId}","evidenceReference":"settlement-intake"}""", "settlement-intake")
        assertThat(received.status).withFailMessage(received.contentAsString).isEqualTo(201)
        val id = mapper.readTree(received.contentAsString).path("id").asString()
        fun inspect(revision: Long, condition: String, location: String) = request("POST", "/api/v1/warehouse/returns/$id/inspect", admin,
            """{"expectedRevision":$revision,"measuredQuantityBase":"17500","condition":"$condition","destinationLocationId":"$location","evidenceReference":"settlement-inspection-$revision","resetConfirmed":false}""", "settlement-inspect-$revision")
        assertThat(inspect(0, "DAMAGED", case.input.targetLocationId.toString()).status).isEqualTo(200)
        val before = mapper.readTree(settlement(case).contentAsString)
        assertThat(before.path("outstandingBase").asString()).isEqualTo("17500")
        fun closeBody(revision: Long) = """{"expectedRevision":$revision,"workOrderRevision":${summary(admin, receipt.workOrder).path("revisions").path("workOrderRevision").asLong()},"reason":"Inspected residual physically returned"}"""
        val blocked = request("POST", "/api/work-orders/${receipt.workOrder}/materials/settlement", admin,
            closeBody(before.path("revision").asLong()), "settlement-too-early")
        assertThat(blocked.status).isEqualTo(409)
        assertThat(mapper.readTree(blocked.contentAsString).path("code").asString()).isEqualTo("MATERIAL_OBLIGATION_OUTSTANDING")
        val accepted = inspect(1, "SERVICEABLE", receipt.stock.bin)
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        val after = mapper.readTree(settlement(case).contentAsString)
        assertThat(after.path("outstandingBase").asString()).isEqualTo("0")
        val line = after.path("obligations").path("lines").single()
        assertThat(line.path("issuedBase").asString()).isEqualTo("100000")
        assertThat(line.path("usedBase").asString()).isEqualTo("82500")
        assertThat(line.path("returnedBase").asString()).isEqualTo("17500")
        assertThat(line.path("settledReturnBase").asString()).isEqualTo("17500")
        assertThat(line.path("stillAccountableBase").asString()).isEqualTo("0")
        val movements = fixture(admin).transaction { scalar("SELECT count(*) FROM inventory_movement") }
        val body = closeBody(after.path("revision").asLong())
        val closed = request("POST", "/api/work-orders/${receipt.workOrder}/materials/settlement", admin, body, "settlement-close")
        assertThat(closed.status).withFailMessage(closed.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(closed.contentAsString).path("materialState").asString()).isEqualTo("CLOSED")
        assertThat(request("POST", "/api/work-orders/${receipt.workOrder}/materials/settlement", admin, body, "settlement-close").contentAsString)
            .isEqualTo(closed.contentAsString)
        assertThat(summary(admin, receipt.workOrder).path("settlementState").asString()).isEqualTo("CLOSED")
        fixture(admin).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(movements)
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("917500")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='CONSUMED'")).isEqualTo("82500")
        }
    }
}
