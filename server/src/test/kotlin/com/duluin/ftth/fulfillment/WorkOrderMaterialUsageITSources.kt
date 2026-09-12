package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.MaterialMode
import com.duluin.ftth.inventory.MaterialUsageRequest
import com.duluin.ftth.inventory.MaterialUsageSelection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WorkOrderMaterialUsageITSources : MaterialUsageFixture() {
    @Test fun `foreign tenant receipt cannot authorize use against a local work order`() {
        val case = usageCase()
        val other = usageCase()
        val before = usageAccounting(case)

        val response = use(case, case.input.copy(lines = other.input.lines))

        assertThat(response.status).isEqualTo(404)
        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @ParameterizedTest @ValueSource(strings = ["accepted", "transit", "warehouse", "discrepancy"])
    fun `partial receipt authorizes only its accepted identity and amount`(mode: String) {
        val case = receiptCase()
        create("locations", case.stock.token, """{"code":"CONSUMED","name":"Consumed","kind":"TRANSIT"}""")
        val receipt = mapper.readTree(received(case))
        val line = receipt.path("lines")[0]
        val identity = when (mode) {
            "accepted", "discrepancy" -> line.path("accepted").path("stockIdentityId").asString()
            "transit" -> line.path("remainder").path("stockIdentityId").asString()
            "warehouse" -> fixture(case.stock.token).transaction {
                scalar("SELECT stock_identity_id FROM inventory_balance_projection WHERE location_id='${case.stock.bin}' AND quantity_base>0")
            }
            else -> error("Unknown fixture")
        }
        val input = MaterialUsageRequest(0, 1, case.input.workOrderRevision, MaterialMode.MATERIAL_REQUIRED, "measurement", listOf(
            MaterialUsageSelection(UUID.fromString(receipt.path("receiptId").asString()), case.input.lines.single().issueLineId,
                UUID.fromString(identity), if (mode == "discrepancy") "82500" else "50000", case.input.lines.single().baseUnit)))
        val before = accounting(case)

        val response = request("POST", "/api/work-orders/${case.workOrder}/materials/report-use", case.receiver.first, mapper.writeValueAsString(input))

        if (mode == "accepted") {
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
            fixture(case.stock.token).transaction {
                assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='ISSUED'")).isEqualTo("10000")
                assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='CONSUMED'")).isEqualTo("50000")
                assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='IN_TRANSIT'")).isEqualTo("40000")
            }
        } else {
            assertThat(response.status).isEqualTo(409)
            assertThat(accounting(case)).isEqualTo(before)
        }
    }

    @Test fun `unacknowledged dispatched stock cannot be used`() {
        val case = receiptCase()
        val before = accounting(case)
        val input = MaterialUsageRequest(0, 1, case.input.workOrderRevision, MaterialMode.MATERIAL_REQUIRED, "measurement", listOf(
            MaterialUsageSelection(case.input.issueId, case.input.lines.single().issueLineId,
                case.input.lines.single().stockIdentityId, "82500", case.input.lines.single().baseUnit)))

        val response = request("POST", "/api/work-orders/${case.workOrder}/materials/report-use", case.receiver.first, mapper.writeValueAsString(input))

        assertThat(response.status).isEqualTo(404)
        assertThat(accounting(case)).isEqualTo(before)
    }

    @ParameterizedTest @ValueSource(strings = ["reassigned", "revoked", "other-technician"])
    fun `assignment and field permission must still authorize first physical use`(mode: String) {
        val case = usageCase()
        val receipt = case.receipt
        val replacement = technician(receipt.stock.token)
        val token = when (mode) {
            "reassigned" -> { assign(receipt.stock.token, receipt.workOrder, replacement.second); receipt.receiver.first }
            "revoked" -> {
                assertThat(request("PUT", "/api/users/${receipt.receiver.second}/access", receipt.stock.token, """{"roleIds":[],"areaIds":[]}""").status).isEqualTo(200)
                receipt.receiver.first
            }
            "other-technician" -> {
                assertThat(request("POST", "/api/work-orders/${receipt.workOrder}/assign", receipt.stock.token,
                    """{"technicianIds":["${receipt.receiver.second}","${replacement.second}"]}""").status).isEqualTo(200)
                replacement.first
            }
            else -> error("Unknown fixture")
        }
        val before = usageAccounting(case)

        val response = request("POST", "/api/work-orders/${receipt.workOrder}/materials/report-use", token, mapper.writeValueAsString(case.input))

        assertThat(response.status).isIn(403, 404, 409)
        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @Test fun `fractional EA quantities never debit acknowledged consumables`() {
        val case = usageCase(fungible = true)
        val before = usageAccounting(case)

        val response = use(case, case.input.copy(lines = case.input.lines.map { it.copy(quantityBase = "1.5") }))

        assertThat(response.status).isEqualTo(400)
        assertThat(usageAccounting(case)).isEqualTo(before)
    }
}
