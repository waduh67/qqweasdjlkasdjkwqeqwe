package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseDispositionIT : WarehouseDispositionFixture() {
    @ParameterizedTest
    @ValueSource(strings = ["SCRAP", "LOSS"])
    fun `independently approved disposal settles only the returned remnant and keeps its physical audit`(action: String) {
        val prepared = dispositionCase(WarehouseDispositionAction.valueOf(action))
        val case = prepared.residual
        val receipt = case.usage.receipt
        val admin = prepared.token
        val checker = prepared.checker
        val body = mapper.writeValueAsString(prepared.input)
        val draft = request("POST", "/api/v1/warehouse/dispositions", admin, body, "scrap-remnant")
        assertThat(draft.status).withFailMessage(draft.contentAsString).isEqualTo(201)
        assertThat(request("POST", "/api/v1/warehouse/dispositions", admin, body, "scrap-remnant").contentAsString).isEqualTo(draft.contentAsString)
        val document = mapper.readTree(draft.contentAsString).path("id").asString()
        assertThat(mapper.readTree(settlement(case).contentAsString).path("outstandingBase").asString()).isEqualTo("17500")
        val approval = request("POST", "/api/v1/warehouse/approvals/request", admin,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", "scrap-approval")
        assertThat(approval.status).withFailMessage(approval.contentAsString).isEqualTo(201)
        val decision = """{"requestId":"${mapper.readTree(approval.contentAsString).path("requestId").asString()}","expectedRevision":0,"decision":"APPROVE","reason":"Independent damage evidence reviewed"}"""
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", admin, decision, "scrap-self-approval").status).isEqualTo(403)
        val approved = request("POST", "/api/v1/warehouse/approvals/decide", checker.first, decision, "scrap-independent-decision")
        assertThat(approved.status).withFailMessage(approved.contentAsString).isEqualTo(200)
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", checker.first, decision, "scrap-independent-decision").contentAsString)
            .isEqualTo(approved.contentAsString)
        val settled = mapper.readTree(settlement(case).contentAsString)
        assertThat(settled.path("outstandingBase").asString()).isEqualTo("0")
        val line = settled.path("obligations").path("lines").single()
        assertThat(line.path("returnedBase").asString()).isEqualTo("17500")
        assertThat(line.path("settledReturnBase").asString()).isEqualTo("17500")
        val movements = fixture(admin).transaction { scalar("SELECT count(*) FROM inventory_movement") }
        val workRevision = summary(admin, receipt.workOrder).path("revisions").path("workOrderRevision").asLong()
        val closed = request("POST", "/api/work-orders/${receipt.workOrder}/materials/settlement", admin,
            """{"expectedRevision":${settled.path("revision").asLong()},"workOrderRevision":$workRevision,"reason":"Returned damaged remnant disposed with independent approval"}""", "scrap-settlement")
        assertThat(closed.status).withFailMessage(closed.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(closed.contentAsString).path("materialState").asString()).isEqualTo("CLOSED")
        fixture(admin).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(movements)
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("900000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='CONSUMED'")).isEqualTo("82500")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='${if (action == "LOSS") "LOST" else "DISPOSED"}' AND condition='${if (action == "LOSS") "DAMAGED" else "SCRAP"}'")).isEqualTo("17500")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='$action'")).isEqualTo("1")
        }
    }
}
