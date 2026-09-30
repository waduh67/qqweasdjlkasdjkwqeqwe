package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.ReceiptRealStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

@Import(ReceiptRealStorage::class)
class WarehouseNumericCountIT : WarehouseNumericLifecycleFixture() {
    @Test fun `actual inspection invalidates its measured position while a partial bin count preserves the new remnant`() {
        val case = numericCase()
        val usage = numericUse(case)
        val installed = numericInstall(case)
        val signature = numericHandover(case, installed)
        val binCount = measuredCount(case, case.stock.bin)
        lateinit var inspectionCount: String
        numericReturn(case, usage) { _, remnant -> inspectionCount = measuredCount(case, case.stock.inspection, remnant) }
        val before = numericTotals(case)
        val stale = request("POST", "$inspectionCount/submit", case.stock.token, """{"expectedRevision":2}""", "numeric-count-stale")
        assertThat(stale.status).withFailMessage(stale.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(stale.contentAsString).path("code").asString()).isEqualTo("COUNT_STALE")
        val unchanged = saveCommand("$binCount/submit", case.stock.token, """{"expectedRevision":2}""", "numeric-count-unchanged")
        assertThat(mapper.readTree(unchanged.original).path("state").asString()).isEqualTo("POSTED")
        assertThat(numericTotals(case)).isEqualTo(before)
        numericComplete(case, signature)
        saveCommand("/api/work-orders/${case.workOrder}/approve", case.stock.token, "{}", "numeric-count-approve")
        assertThat(numericTotals(case)).isEqualTo("917500|82500|0|9|1|1|10|1")
        fixture(case.stock.token).transaction {
            for (root in listOf(binCount, inspectionCount)) {
                assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='${root.substringAfterLast('/')}'")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_cycle_count WHERE document_id='${root.substringAfterLast('/')}'")).isEqualTo("1")
            }
            assertThat(scalar("SELECT state FROM fulfillment_checkpoint WHERE work_order_id='${case.workOrder}'")).isEqualTo("APPLIED")
        }
    }

    private fun measuredCount(case: NumericCase, location: String, identity: String? = null): String {
        val counter = mapper.readTree(request("GET", "/api/me", case.stock.token).contentAsString).path("id").asString()
        val position = fixture(case.stock.token).transaction {
            mapper.readTree(scalar("""SELECT jsonb_build_object('id',id,'quantity',quantity_base::text)
                FROM inventory_balance_projection WHERE location_id='$location' AND quantity_base>0 AND base_unit='MM'
                ${identity?.let { "AND stock_identity_id='$it'" }.orEmpty()}"""))
        }
        val draft = create("counts", case.stock.token, mapper.writeValueAsString(mapOf("locationId" to location,
            "partialLocation" to true, "reason" to "Count the selected cable position during a real return",
            "entries" to listOf(mapOf("balanceId" to position.path("id").asString(), "counterId" to counter)))))
        val root = "/api/v1/warehouse/counts/${draft.path("id").asString()}"
        saveCommand("$root/start", case.stock.token, """{"expectedRevision":0}""", "numeric-count-start-$location")
        saveCommand("$root/observe", case.stock.token, mapper.writeValueAsString(mapOf("expectedRevision" to 1,
            "balanceId" to position.path("id").asString(), "quantityBase" to position.path("quantity").asString(),
            "reason" to "Measured selected position", "documentReference" to "SCOPED-CABLE-COUNT")), "numeric-count-observe-$location")
        return root
    }
}
