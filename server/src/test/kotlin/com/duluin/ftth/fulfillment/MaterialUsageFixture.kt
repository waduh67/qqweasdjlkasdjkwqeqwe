package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.MaterialMode
import com.duluin.ftth.inventory.MaterialUsageRequest
import com.duluin.ftth.inventory.MaterialUsageSelection
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

abstract class MaterialUsageFixture : MaterialReceiptFixture() {
    protected data class UsageCase(val receipt: ReceiptCase, val input: MaterialUsageRequest)

    protected fun usageCase(serial: Boolean = false, fungible: Boolean = false): UsageCase {
        val case = receiptCase(serial, fungible)
        create("locations", case.stock.token, """{"code":"CONSUMED","name":"Physical consumption sink","kind":"TRANSIT"}""")
        val acknowledgement = acknowledge(case, case.input.copy(lines = case.input.lines.map {
            it.copy(acceptedBase = if (serial) "1" else if (fungible) "100" else "100000", missingBase = "0")
        }))
        assertThat(acknowledgement.status).withFailMessage(acknowledgement.contentAsString).isEqualTo(200)
        val receipt = mapper.readTree(acknowledgement.contentAsString)
        return UsageCase(case, MaterialUsageRequest(0, 1, case.input.workOrderRevision, MaterialMode.MATERIAL_REQUIRED,
            "measured-use", listOf(MaterialUsageSelection(UUID.fromString(receipt.path("receiptId").asString()),
                case.input.lines.single().issueLineId, UUID.fromString(receipt.path("lines")[0].path("accepted").path("stockIdentityId").asString()),
                if (serial) "1" else if (fungible) "82" else "82500", case.input.lines.single().baseUnit))))
    }

    protected fun use(case: UsageCase, input: MaterialUsageRequest = case.input, key: String = "measured-use") =
        request("POST", "/api/work-orders/${case.receipt.workOrder}/materials/report-use", case.receipt.receiver.first,
            mapper.writeValueAsString(input), key)

    protected fun used(case: UsageCase): String {
        val response = use(case)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return response.contentAsString
    }

    protected fun usageAccounting(case: UsageCase): String = fixture(case.receipt.stock.token).transaction {
        scalar("""SELECT concat_ws('|',
            (SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE status='ISSUED' AND custody_owner_kind='TECHNICIAN'),
            (SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE status='CONSUMED'),
            (SELECT count(*) FROM inventory_usage_snapshot),(SELECT count(*) FROM inventory_material_usage),
            (SELECT count(*) FROM inventory_material_usage_line),(SELECT count(*) FROM inventory_customer_material_fact),
            (SELECT count(*) FROM inventory_movement),(SELECT count(*) FROM inventory_operation),
            (SELECT count(*) FROM inventory_segment),(SELECT count(*) FROM inventory_fulfillment_effect))""")
    }
}
