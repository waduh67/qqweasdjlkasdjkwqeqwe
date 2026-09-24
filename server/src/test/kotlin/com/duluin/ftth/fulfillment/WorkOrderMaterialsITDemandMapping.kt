package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkOrderMaterialsITDemandMapping : MaterialWorkflowFixture() {
    @Test fun `submitted line mapping supports the first explicit partial reservation without deriving demand IDs`() {
        val setup = setupReceipt()
        receiveStock(setup, "1000000")
        val token = setup.token
        val work = workOrder(token)
        putPlan(token, work, plan(token, work, "[${line(setup.cable)}]"))
        val draft = summary(token, work)
        assertThat(draft.path("lines")[0].path("demandLineId").isNull).isTrue()
        val submitted = action(token, work, "submit-request", command(token, work, 1))
        val line = submitted.path("lines")[0]
        val demandLine = line.path("demandLineId").asString()
        assertThat(demandLine).isNotBlank().isNotEqualTo(line.path("planLineId").asString())
        val document = submitted.path("demandDocumentId").asString()
        val reserved = request("POST", "/api/v1/warehouse/material-requests/$document/reserve", token,
            mapper.writeValueAsString(mapOf("expectedRevision" to submitted.path("demandRevision").asLong(),
                "workOrderRevision" to submitted.path("revisions").path("workOrderRevision").asLong(), "planRevision" to 1,
                "lines" to listOf(mapOf("demandLineId" to demandLine, "partialQuantityBase" to "60000")))))
        assertThat(reserved.status).withFailMessage(reserved.contentAsString).isEqualTo(200)
        val after = summary(token, work).path("lines")[0]
        assertThat(after.path("demandLineId").asString()).isEqualTo(demandLine)
        assertThat(after.path("planLineId").asString()).isEqualTo(line.path("planLineId").asString())
        assertThat(after.path("reservedUnpickedBase").asString()).isEqualTo("60000")
        assertThat(after.path("backorderBase").asString()).isEqualTo("40000")
        fixture(token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE base_unit='MM'")).isEqualTo("1000000")
        }
    }
}
