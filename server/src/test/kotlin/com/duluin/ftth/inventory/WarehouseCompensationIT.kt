package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseCompensationIT : WarehouseDispositionFixture() {
    @ParameterizedTest
    @ValueSource(strings = ["LOSS", "SCRAP"])
    fun `approved compensation restores the exact disposed remnant to quarantine and reopens its return obligation`(action: String) {
        val case = dispositionCase(WarehouseDispositionAction.valueOf(action))
        val original = disposition(case)
        val disposed = dispositionDecision(case, dispositionApproval(case, original))
        assertThat(disposed.status).withFailMessage(disposed.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(settlement(case.residual).contentAsString).path("outstandingBase").asString()).isEqualTo("0")
        val policy = request("PUT", "/api/v1/warehouse/settings/policy", case.token,
            """{"expectedRevision":1,"currency":"IDR","expiryHours":24,"warehouseIds":["${case.residual.input.targetLocationId}","${case.sink}"],"rules":[{"operation":"$action","tiers":[{"minimumMinor":"1","userIds":["${case.checker.second}"],"roleIds":[]}]},{"operation":"ADJUSTMENT","tiers":[{"minimumMinor":"1","userIds":["${case.checker.second}"],"roleIds":[]}]}]}""")
        assertThat(policy.status).withFailMessage(policy.contentAsString).isEqualTo(200)
        val body = """{"expectedRevision":1,"expectedReturnRevision":2,"destinationLocationId":"${case.residual.input.targetLocationId}","reason":"Original disposition disproved by witnessed physical recovery","evidenceReference":"witnessed-recovered-remnant"}"""
        val path = "/api/v1/warehouse/dispositions/$original/compensations"
        val draft = request("POST", path, case.token, body, "compensation-request")
        assertThat(draft.status).withFailMessage(draft.contentAsString).isEqualTo(201)
        assertThat(request("POST", path, case.token, body, "compensation-request").contentAsString).isEqualTo(draft.contentAsString)
        assertThat(mapper.readTree(settlement(case.residual).contentAsString).path("outstandingBase").asString()).isEqualTo("0")
        val document = mapper.readTree(draft.contentAsString).path("id").asString()
        val approval = dispositionApproval(case, document, "compensation-approval")
        assertThat(dispositionDecision(case, approval, "compensation-self-decision", token = case.token).status).isEqualTo(403)
        val reversed = dispositionDecision(case, approval, "compensation-decision")
        assertThat(reversed.status).withFailMessage(reversed.contentAsString).isEqualTo(200)
        assertThat(dispositionDecision(case, approval, "compensation-decision").contentAsString).isEqualTo(reversed.contentAsString)
        val unsettled = mapper.readTree(settlement(case.residual).contentAsString)
        assertThat(unsettled.path("outstandingBase").asString()).isEqualTo("17500")
        assertThat(unsettled.path("obligations").path("lines").single().path("returnedBase").asString()).isEqualTo("17500")
        fixture(case.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='$document' AND kind='REVERSAL' AND compensates_movement_id=(SELECT id FROM inventory_movement WHERE document_id='$original')"))
                .isEqualTo("1")
            assertThat(scalar("SELECT concat_ws('|',state,revision) FROM inventory_document WHERE id='$original'")).isEqualTo("POSTED|1")
            assertThat(scalar("SELECT concat_ws('|',quantity_base,status,condition,legal_owner) FROM inventory_balance_projection WHERE stock_identity_id='${case.identity}' AND quantity_base>0"))
                .isEqualTo("17500|QUARANTINE|QUARANTINE|ISP")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("900000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='CONSUMED'")).isEqualTo("82500")
        }
        assertThat(request("POST", path, case.token, body, "duplicate-compensation").status).isEqualTo(409)
        val inspected = request("POST", "/api/v1/warehouse/returns/${case.returnId}/inspect", case.token,
            """{"expectedRevision":3,"measuredQuantityBase":"17500","condition":"SERVICEABLE","destinationLocationId":"${case.residual.usage.receipt.stock.bin}","evidenceReference":"recovered-remnant-inspected","resetConfirmed":false}""", "compensation-inspection")
        assertThat(inspected.status).withFailMessage(inspected.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(settlement(case.residual).contentAsString).path("outstandingBase").asString()).isEqualTo("0")
        fixture(case.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("917500")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='REVERSAL'")).isEqualTo("1")
        }
    }
}
