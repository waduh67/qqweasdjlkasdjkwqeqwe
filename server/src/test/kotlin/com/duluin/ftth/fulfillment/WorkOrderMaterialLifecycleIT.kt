package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkOrderMaterialLifecycleIT : MaterialUsageFixture() {
    @Test fun `cancel releases unpicked reservations without a physical posting`() {
        val case = issuedSetup()
        val before = fixture(case.stock.token).transaction {
            scalar("SELECT count(*) FROM inventory_movement")
        }

        val response = request("POST", "/api/work-orders/${case.workOrder}/cancel", case.stock.token,
            """{"reason":"Customer cancelled before collection"}""")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        fixture(case.stock.token).transaction {
            assertThat(scalar("SELECT coalesce(sum(reserved_unpicked_base),0) FROM inventory_reservation")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(before)
        }
    }

    @Test fun `cancel rejects picked material until explicit unpick`() {
        val case = issuedSetup()
        action(case.stock.token, case.workOrder, "pick", pickBody(case))

        val response = request("POST", "/api/work-orders/${case.workOrder}/cancel", case.stock.token,
            """{"reason":"Picked material still in staging"}""")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        fixture(case.stock.token).transaction {
            assertThat(scalar("SELECT status FROM work_order WHERE id='${case.workOrder}'")).isEqualTo("ASSIGNED")
        }
    }

    @Test fun `cancel after use preserves consumed material and exposes the residual obligation`() {
        val case = usageCase()
        used(case)
        val before = usageAccounting(case)

        val response = request("POST", "/api/work-orders/${case.receipt.workOrder}/cancel", case.receipt.stock.token,
            """{"reason":"Service abandoned after physical use"}""")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(usageAccounting(case)).isEqualTo(before).startsWith("17500|82500|1|1|1|1|")
        val settlement = request("GET", "/api/work-orders/${case.receipt.workOrder}/materials/settlement", case.receipt.stock.token)
        assertThat(settlement.status).withFailMessage(settlement.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(settlement.contentAsString).path("outstandingBase").asString()).isEqualTo("17500")
    }

    @Test fun `reassignment preserves custody and blocks old assignee new use`() {
        val case = usageCase()
        val replacement = technician(case.receipt.stock.token)
        val before = usageAccounting(case)

        assign(case.receipt.stock.token, case.receipt.workOrder, replacement.second)

        assertThat(use(case).status).isEqualTo(403)
        assertThat(usageAccounting(case)).isEqualTo(before)
        val own = request("GET", "/api/work-orders/${case.receipt.workOrder}/materials/settlement", case.receipt.receiver.first)
        assertThat(own.status).withFailMessage(own.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(own.contentAsString).path("outstandingBase").asString()).isEqualTo("100000")
    }

    @Test fun `old custodian can dispatch only their residual into nonavailable return transit`() {
        val case = usageCase()
        val usage = mapper.readTree(used(case))
        val replacement = technician(case.receipt.stock.token)
        assign(case.receipt.stock.token, case.receipt.workOrder, replacement.second)
        val destination = create("locations", case.receipt.stock.token,
            """{"code":"WO_RETURN","name":"Return quarantine","kind":"QUARANTINE"}""").path("id").asString()
        val revision = summary(case.receipt.stock.token, case.receipt.workOrder).path("revisions").path("workOrderRevision").asLong()
        val source = case.input.lines.single()
        val input = mapper.writeValueAsString(mapOf("workOrderRevision" to revision,
            "receiptId" to source.receiptId, "issueLineId" to source.issueLineId,
            "usageId" to usage.path("usageId").asString(),
            "stockIdentityId" to usage.path("lines")[0].path("remainder").path("stockIdentityId").asString(),
            "quantityBase" to "17500", "baseUnit" to "MM", "targetLocationId" to destination,
            "reason" to "Returning residual after reassignment", "evidenceReference" to "signed-return"))

        val response = request("POST", "/api/work-orders/${case.receipt.workOrder}/materials/return",
            case.receipt.receiver.first, input, "residual-return")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        fixture(case.receipt.stock.token).transaction {
            assertThat(scalar("SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE status='CONSUMED'")).isEqualTo("82500")
            assertThat(scalar("SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("900000")
            assertThat(scalar("SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE status='IN_TRANSIT'")).isEqualTo("17500")
        }
    }

    @Test fun `forced material close rejects seventeen point five metres without effects`() {
        val case = usageCase()
        used(case)
        val before = usageAccounting(case)
        val revision = summary(case.receipt.stock.token, case.receipt.workOrder).path("revisions").path("workOrderRevision").asLong()

        val response = request("POST", "/api/work-orders/${case.receipt.workOrder}/materials/settlement", case.receipt.stock.token,
            """{"expectedRevision":0,"workOrderRevision":$revision,"reason":"Force material closure"}""", "force-close")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("MATERIAL_OBLIGATION_OUTSTANDING")
        assertThat(usageAccounting(case)).isEqualTo(before)
    }
}
