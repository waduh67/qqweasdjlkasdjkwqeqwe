package com.duluin.ftth.inventory

import com.duluin.ftth.fulfillment.MaterialLifecycleFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseDispositionIT : MaterialLifecycleFixture() {
    @Test fun `independently approved scrap settles only the damaged returned remnant and keeps its physical audit`() {
        val case = residualCase()
        val receipt = case.usage.receipt
        val admin = receipt.stock.token
        val residual = dispatchedResidual(case)
        assertThat(acknowledgeResidual(case, residual).status).isEqualTo(200)
        val intake = request("POST", "/api/v1/warehouse/returns", admin,
            """{"origin":"MATERIAL_RESIDUAL","sourceDocumentId":"$residual","quarantineLocationId":"${case.input.targetLocationId}","evidenceReference":"damaged-remnant-intake"}""")
        assertThat(intake.status).withFailMessage(intake.contentAsString).isEqualTo(201)
        val returned = mapper.readTree(intake.contentAsString)
        val returnId = returned.path("id").asString()
        val identity = returned.path("stockIdentityId").asString()
        val inspected = request("POST", "/api/v1/warehouse/returns/$returnId/inspect", admin,
            """{"expectedRevision":0,"measuredQuantityBase":"17500","condition":"DAMAGED","destinationLocationId":"${case.input.targetLocationId}","evidenceReference":"damaged-remnant-inspection","resetConfirmed":false}""")
        assertThat(inspected.status).withFailMessage(inspected.contentAsString).isEqualTo(200)
        val sink = create("locations", admin, """{"code":"SCRAP_SINK","name":"Approved scrap","kind":"DISPOSED"}""").path("id").asString()
        val checker = user(admin, setOf("inventory.approval.view", "inventory.approval.decide"))
        val principal = mapper.readTree(request("GET", "/api/users/${checker.second}", admin).contentAsString)
        assertThat(request("PUT", "/api/users/${checker.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        for (location in listOf(case.input.targetLocationId.toString(), sink)) {
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${checker.second}/$location", admin,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        }
        val policy = request("PUT", "/api/v1/warehouse/settings/policy", admin,
            """{"expectedRevision":0,"currency":"IDR","expiryHours":24,"warehouseIds":["${case.input.targetLocationId}","$sink"],"rules":[{"operation":"SCRAP","tiers":[{"minimumMinor":"1","userIds":["${checker.second}"],"roleIds":[]}]}]}""")
        assertThat(policy.status).withFailMessage(policy.contentAsString).isEqualTo(200)
        val body = """{"sourceDocumentId":"$returnId","expectedRevision":1,"stockIdentityId":"$identity","quantityBase":"17500","baseUnit":"MM","destinationLocationId":"$sink","action":"SCRAP","reason":"Measured remnant is irreparably damaged","evidenceReference":"signed-scrap-assessment"}"""
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
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='DISPOSED' AND condition='SCRAP'")).isEqualTo("17500")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='SCRAP'")).isEqualTo("1")
        }
    }
}
