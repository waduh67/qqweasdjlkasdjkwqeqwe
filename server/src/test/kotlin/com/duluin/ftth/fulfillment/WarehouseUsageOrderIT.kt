package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import java.util.UUID

@Import(ReceiptRealStorage::class)
class WarehouseUsageOrderIT : WarehouseNumericLifecycleFixture() {
    @Test fun `ONU installed before first measured cable use still completes the conserved numeric job`() {
        val original = numericCase()
        val installed = numericInstall(original)
        val field = mapper.readTree(request("GET", "/api/work-orders/${original.workOrder}/materials/workbench", original.technician.first).contentAsString)
        assertThat(field.path("useRevision").asLong()).isEqualTo(1)
        assertThat(field.path("latestUsageId").isNull).isTrue()
        val before = numericAudit(original)
        val stale = request("POST", "/api/work-orders/${original.workOrder}/materials/report-use", original.technician.first,
            mapper.writeValueAsString(original.usageInput), "numeric-use")
        assertThat(stale.status).isEqualTo(409)
        assertThat(numericAudit(original)).isEqualTo(before)
        val case = original.copy(usageInput = original.usageInput.copy(expectedRevision = 1,
            workOrderRevision = field.path("workOrderRevision").asLong()))
        val usage = numericUse(case)
        assertThat(mapper.readTree(usage.original).path("useRevision").asLong()).isEqualTo(2)
        assertThat(numericUse(case)).isEqualTo(usage)
        assertSequence(case, "DEPLOYMENT:1,USAGE:2")
        finish(case, usage, installed)
    }

    @Test fun `positive cable delta after ONU installation uses the latest usage predecessor across physical revisions`() {
        val original = numericCase()
        val case = original.copy(usageInput = original.usageInput.copy(evidenceReference = "Measured80m",
            lines = original.usageInput.lines.map { it.copy(quantityBase = "80000") }))
        val first = numericUse(case)
        val installed = numericInstall(case)
        val firstBody = mapper.readTree(first.original)
        val source = case.usageInput.lines.single()
        val revision = summary(case.stock.token, case.workOrder).path("revisions").path("workOrderRevision").asLong()
        val input = MaterialUsageDeltaRequest(2, revision, UUID.fromString(firstBody.path("usageId").asString()),
            source.receiptId, source.issueLineId, UUID.fromString(firstBody.path("lines").single().path("remainder").path("stockIdentityId").asString()),
            "2500", WarehouseBaseUnit.MM, "Measured another2.500m", "Final cable routing")
        val path = "/api/work-orders/${case.workOrder}/materials/correct-use"
        val before = numericAudit(case)
        val stale = request("POST", path, case.technician.first, mapper.writeValueAsString(input.copy(expectedRevision = 1)), "numeric-delta")
        assertThat(stale.status).isEqualTo(409)
        assertThat(numericAudit(case)).isEqualTo(before)
        val delta = saveCommand(path, case.technician.first, mapper.writeValueAsString(input), "numeric-delta")
        assertThat(mapper.readTree(delta.original).path("useRevision").asLong()).isEqualTo(3)
        assertThat(saveCommand(path, case.technician.first, delta.body, delta.key)).isEqualTo(delta)
        assertSequence(case, "USAGE:1,DEPLOYMENT:2,USAGE:3")
        val historic = request("GET", "/api/v1/warehouse/my-material-usage/${firstBody.path("usageId").asString()}", case.technician.first)
        assertThat(historic.status).withFailMessage(historic.contentAsString).isEqualTo(200)
        assertThat(historic.contentAsString).isEqualTo(first.original)
        finish(case, delta, installed)
    }

    private fun assertSequence(case: NumericCase, expected: String) = fixture(case.stock.token).transaction {
        assertThat(scalar("""SELECT string_agg(kind||':'||use_revision,',' ORDER BY use_revision)
            FROM inventory_document WHERE work_order_id='${case.workOrder}' AND kind IN ('USAGE','DEPLOYMENT') AND state='POSTED'"""))
            .isEqualTo(expected)
        assertThat(scalar("""SELECT count(*) FROM inventory_customer_material_fact fact
            JOIN inventory_usage_snapshot usage ON usage.tenant_id=fact.tenant_id AND usage.id=fact.usage_id
            WHERE fact.use_revision<>usage.use_revision""")).isEqualTo("0")
    }

    private fun finish(case: NumericCase, usage: SavedCommand, installed: SavedCommand) {
        val signature = numericHandover(case, installed)
        numericReturn(case, usage)
        val before = numericAudit(case)
        numericComplete(case, signature)
        saveCommand("/api/work-orders/${case.workOrder}/approve", case.stock.token, "{}", "numeric-approve")
        assertThat(numericAudit(case)).isEqualTo(before)
        assertThat(numericTotals(case)).isEqualTo("917500|82500|0|9|1|1|10|1")
    }
}
