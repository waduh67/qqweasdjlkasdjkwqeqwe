package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkOrderMaterialReworkITRaces : MaterialReworkFixture() {
    @Test fun `rework and use serialize against the expected usage revision`() {
        val case = reworkCase()
        val old = mapper.readTree(case.originalUsage)
        val line = case.usage.input.lines.single()
        val input = mapper.writeValueAsString(mapOf("expectedRevision" to 1, "workOrderRevision" to case.input.path("workOrderRevision").asLong(),
            "previousUsageId" to old.path("usageId").asString(), "receiptId" to line.receiptId, "issueLineId" to line.issueLineId,
            "stockIdentityId" to old.path("lines")[0].path("remainder").path("stockIdentityId").asString(), "quantityBase" to "7500",
            "baseUnit" to "MM", "evidenceReference" to "parallel-use", "reason" to "Measured work"))

        val outcomes = race({ rework(case).status }, { request("POST", "/api/work-orders/${case.usage.receipt.workOrder}/materials/correct-use",
            case.usage.receipt.receiver.first, input, "parallel-use").status })

        assertThat(outcomes).containsExactlyInAnyOrder(200, 409)
        fixture(case.usage.receipt.stock.token).transaction {
            assertThat(scalar("SELECT frozen_snapshot FROM inventory_usage_snapshot WHERE use_revision=1")).isEqualTo(case.originalUsage)
        }
    }

    @Test fun `rework and zero-residual close cannot both commit`() {
        val case = reworkCase("100000")
        val receipt = case.usage.receipt
        val state = mapper.readTree(request("GET", "/api/work-orders/${receipt.workOrder}/materials/settlement", receipt.stock.token).contentAsString)

        val outcomes = race({ rework(case).status }, { request("POST", "/api/work-orders/${receipt.workOrder}/materials/settlement", receipt.stock.token,
            mapper.writeValueAsString(mapOf("expectedRevision" to state.path("revision").asLong(), "workOrderRevision" to case.input.path("workOrderRevision").asLong(),
                "reason" to "Attempt concurrent closure")), "close-race").status })

        assertThat(outcomes).containsExactlyInAnyOrder(200, 409)
        fixture(receipt.stock.token).transaction { assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='CONSUMED'")).isEqualTo("100000") }
    }

    private fun race(first: () -> Int, second: () -> Int): List<Int> {
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val futures = listOf(first, second).map { action -> executor.submit<Int> { check(start.await(10, TimeUnit.SECONDS)); action() } }
            start.countDown()
            return futures.map { it.get(45, TimeUnit.SECONDS) }
        }
    }
}
