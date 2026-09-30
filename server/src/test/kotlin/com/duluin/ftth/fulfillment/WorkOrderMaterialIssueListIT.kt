package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkOrderMaterialIssueListIT : MaterialReceiptFixture() {
    @Test fun `warehouse issue totals distinguish dispatch from partial and completed physical receipts`() {
        val case = receiptCase()
        val path = "/api/work-orders/${case.workOrder}/materials/issues"
        fun snapshot() = request("GET", path, case.stock.token).let {
            assertThat(it.status).withFailMessage(it.contentAsString).isEqualTo(200)
            mapper.readTree(it.contentAsString).path("items")[0]
        }
        assertThat(snapshot().path("lines")[0].path("dispatchedBase").asString()).isEqualTo("100000")
        assertThat(snapshot().path("lines")[0].path("acceptedBase").asString()).isEqualTo("0")
        received(case)
        val partial = snapshot()
        assertThat(partial.path("state").asString()).isEqualTo("PART_RECEIVED")
        assertThat(partial.path("revision").asLong()).isEqualTo(3)
        assertThat(partial.path("lines")[0].path("acceptedBase").asString()).isEqualTo("60000")
        val rest = acknowledge(case, case.input.copy(expectedRevision = 3, lines = case.input.lines.map { it.copy(acceptedBase = "40000", missingBase = "0") }), "remaining")
        assertThat(rest.status).withFailMessage(rest.contentAsString).isEqualTo(200)
        val before = accounting(case)
        val complete = snapshot()
        assertThat(complete.path("state").asString()).isEqualTo("RECEIVED")
        assertThat(complete.path("revision").asLong()).isEqualTo(4)
        assertThat(complete.path("lines")[0].path("dispatchedBase").asString()).isEqualTo("100000")
        assertThat(complete.path("lines")[0].path("acceptedBase").asString()).isEqualTo("100000")
        assertThat(accounting(case)).isEqualTo(before)
    }
}
