package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.node.ObjectNode

class WorkOrderMaterialReworkITGuards : MaterialReworkFixture() {
    @Test fun `direct reservation owner rejects stale rework evidence too`() {
        val case = reworkCase()
        assertThat(rework(case).status).isEqualTo(200)
        val receipt = case.usage.receipt
        val state = summary(receipt.stock.token, receipt.workOrder)
        addReworkEvidence(case.usage, "ONT")

        val response = request("POST", "/api/v1/warehouse/material-requests/${state.path("demandDocumentId").asString()}/reserve", receipt.stock.token,
            mapper.writeValueAsString(mapOf("expectedRevision" to state.path("demandRevision").asLong(), "planRevision" to 2,
                "workOrderRevision" to case.input.path("workOrderRevision").asLong())), "direct-stale-reserve")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
    }

    @ParameterizedTest @ValueSource(strings = ["wo", "plan", "usage", "prior-evidence", "evidence", "changed-evidence", "cancelled", "done", "negative", "empty", "reason"])
    fun `invalid rework leaves the plan and physical history unchanged`(mode: String) {
        val case = reworkCase()
        val input = mapper.treeToValue(case.input, ObjectNode::class.java)
        when (mode) {
            "wo" -> input.put("workOrderRevision", input.path("workOrderRevision").asLong() + 1)
            "plan" -> input.put("expectedRevision", 2)
            "usage" -> input.put("expectedUsageRevision", 2)
            "prior-evidence" -> input.put("previousEvidenceRevision", "0".repeat(64))
            "evidence" -> input.put("evidenceRevision", "0".repeat(64))
            "changed-evidence" -> addReworkEvidence(case.usage, "ONT")
            "cancelled" -> assertThat(request("POST", "/api/work-orders/${case.usage.receipt.workOrder}/cancel", case.usage.receipt.stock.token,
                """{"reason":"Cancelled rework"}""").status).isEqualTo(200)
            "done" -> resubmit(case)
            "negative" -> {
                val line = mapper.treeToValue(input.path("deltas")[0], ObjectNode::class.java).put("quantityBase", "-1")
                input.set("deltas", mapper.createArrayNode().add(line))
            }
            "empty" -> input.set("deltas", mapper.createArrayNode())
            "reason" -> input.put("reason", " ")
            else -> error("Unknown mode")
        }
        val before = usageAccounting(case.usage)

        val result = rework(case, input)

        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(if (mode in setOf("negative", "empty", "reason")) 400 else 409)
        assertThat(usageAccounting(case.usage)).isEqualTo(before)
        fixture(case.usage.receipt.stock.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_material_plan")).isEqualTo("1") }
    }

    @Test fun `unassigned technician cannot authorize a rework plan`() {
        val case = reworkCase()
        val other = technician(case.usage.receipt.stock.token)

        val response = rework(case, token = other.first)

        assertThat(response.status).isEqualTo(403)
    }

    @Test fun `rework exact replay is stable while changed payload and new key conflict`() {
        val case = reworkCase()
        val first = rework(case)
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(200)
        val before = usageAccounting(case.usage)

        val replay = rework(case)

        assertThat(replay.status).isEqualTo(200)
        assertThat(replay.contentAsString).isEqualTo(first.contentAsString)
        assertThat(rework(case, mapper.treeToValue(case.input, ObjectNode::class.java).put("reason", "Different intent")).status).isEqualTo(409)
        assertThat(rework(case, key = "other-key").status).isEqualTo(409)
        assertThat(usageAccounting(case.usage)).isEqualTo(before)
    }

    @Test fun `changed evidence rejects replay and reservation of the stale rework plan`() {
        val case = reworkCase()
        assertThat(rework(case).status).isEqualTo(200)
        addReworkEvidence(case.usage, "ONT")
        val receipt = case.usage.receipt

        val result = request("POST", "/api/work-orders/${receipt.workOrder}/materials/reserve", receipt.stock.token,
            command(receipt.stock.token, receipt.workOrder, 2), "stale-reserve")

        assertThat(result.status).isEqualTo(409)
        assertThat(rework(case).status).isEqualTo(409)
        fixture(receipt.stock.token).transaction { assertThat(scalar("SELECT sum(reserved_unpicked_base) FROM inventory_reservation")).isEqualTo("0") }
    }
}
