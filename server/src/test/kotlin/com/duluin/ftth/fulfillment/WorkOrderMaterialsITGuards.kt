package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import java.util.UUID

class WorkOrderMaterialsITGuards : MaterialWorkflowFixture() {
    @ParameterizedTest
    @ValueSource(strings = ["EMPTY", "NONE_LINES", "NONE_REASON", "DUPLICATE", "ZERO", "FRACTION", "OVERFLOW", "UNIT", "FOREIGN_SKU", "ACTOR", "CUSTOMER", "ALLOCATION", "NUMERIC", "STALE_WO", "STALE_PLAN"])
    fun `malformed forged inaccessible and stale plans cannot write snapshots`(scenario: String) {
        val setup = setupReceipt()
        val token = setup.token
        val id = workOrder(token)
        val valid = line(setup.cable)
        var body = plan(token, id, "[$valid]")
        body = when (scenario) {
            "EMPTY" -> plan(token, id, "[]")
            "NONE_LINES" -> plan(token, id, "[$valid]", mode = "NONE", reason = "Inspection")
            "NONE_REASON" -> plan(token, id, "[]", mode = "NONE")
            "DUPLICATE" -> plan(token, id, "[$valid,$valid]")
            "ZERO" -> body.replace("100000", "0")
            "FRACTION" -> body.replace("100000", "1.5")
            "OVERFLOW" -> body.replace("100000", "9223372036854775808")
            "UNIT" -> body.replace("MM", "EA")
            "FOREIGN_SKU" -> body.replace(setup.cable, UUID.randomUUID().toString())
            "ACTOR" -> body.dropLast(1) + ",\"actorId\":\"${UUID.randomUUID()}\"}"
            "CUSTOMER" -> body.dropLast(1) + ",\"customerId\":\"${UUID.randomUUID()}\"}"
            "ALLOCATION" -> body.dropLast(1) + ",\"allocations\":[]}"
            "NUMERIC" -> body.replace("\"100000\"", "100000")
            "STALE_WO" -> body.replace("\"workOrderRevision\":0", "\"workOrderRevision\":999")
            "STALE_PLAN" -> plan(token, id, "[$valid]", 9)
            else -> error(scenario)
        }
        val response = request("PUT", "/api/work-orders/$id/materials/plan", token, body)
        assertThat(response.status).withFailMessage(response.contentAsString).isIn(400, 404, 409)
        fixture(token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_material_plan")).isEqualTo("0") }
    }
    @Test fun `foreign WO bounded history and future commands fail explicitly`() {
        val token = tenant()
        val id = workOrder(token)
        val foreign = tenant()
        assertThat(request("GET", "/api/work-orders/$id/materials", foreign).status).isEqualTo(404)
        assertThat(request("GET", "/api/work-orders/$id/materials/history?size=101", token).status).isEqualTo(400)
        assertThat(request("POST", "/api/work-orders/$id/materials/dispatch", token, "{}").status).isEqualTo(400)
        assertThat(request("POST", "/api/work-orders/$id/materials/acknowledge", token, "{}").status).isEqualTo(400)
        assertThat(request("POST", "/api/work-orders/$id/materials/report-use", token, "{}").status).isEqualTo(409)
        assertThat(request("POST", "/api/work-orders/$id/materials/submit-request", token, command(token, id, 0)).status).isEqualTo(409)
        assertThat(request("POST", "/api/work-orders/$id/cancel", token, """{"reason":"Cancelled"}""").status).isEqualTo(200)
        val body = plan(token, id, "[]", mode = "NONE", reason = "Inspection")
        assertThat(request("PUT", "/api/work-orders/$id/materials/plan", token, body).status).isEqualTo(409)
    }
}
