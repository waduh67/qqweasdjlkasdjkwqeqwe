package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.PostingJdbcProbe
import com.duluin.ftth.inventory.TestPostingPhase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkOrderMaterialUsageITConcurrency : MaterialUsageFixture() {
    @ParameterizedTest @ValueSource(strings = ["same-key", "different-key", "partial-overspend", "scope", "reassignment"])
    fun `concurrent reporting preserves one physical use and current authority`(mode: String) {
        val case = usageCase()
        val receipt = case.receipt
        val observer = fixture(receipt.stock.token)
        val replacement = if (mode == "reassignment") technician(receipt.stock.token).second else null
        val firstPosting = CountDownLatch(1)
        val competingStarted = CountDownLatch(1)
        val releasePosting = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            PostingJdbcProbe(context, TestPostingPhase.LEGS) {
                firstPosting.countDown()
                check(releasePosting.await(30, TimeUnit.SECONDS))
            }.use {
                val first = executor.submit<Pair<Int, String>> { val result = use(case); result.status to result.contentAsString }
                check(firstPosting.await(30, TimeUnit.SECONDS))
                val second = executor.submit<Pair<Int, String>> {
                    competingStarted.countDown()
                    val result = when (mode) {
                        "same-key" -> use(case)
                        "different-key" -> use(case, key = "competing-use")
                        "partial-overspend" -> use(case, case.input.copy(lines = case.input.lines.map { it.copy(quantityBase = "20000") }), "remaining-use")
                        "scope" -> request("PUT", "/api/v1/warehouse/settings/scopes/${receipt.receiver.second}/${receipt.field}", receipt.stock.token,
                            """{"expectedRevision":1,"active":false}""")
                        "reassignment" -> request("POST", "/api/work-orders/${receipt.workOrder}/assign", receipt.stock.token, """{"technicianIds":["$replacement"]}""")
                        else -> error("Unknown fixture")
                    }
                    result.status to result.contentAsString
                }

                check(competingStarted.await(10, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                var blocked = false
                while (!blocked && System.nanoTime() < deadline) {
                    blocked = observer.transaction {
                        scalar("""SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user
                            AND pid<>pg_backend_pid() AND wait_event_type='Lock'""").toLong() > 0L
                    }
                }
                assertThat(blocked).describedAs("competing request reached the database lock while first posting was held").isTrue()
                releasePosting.countDown()
                val winner = first.get(45, TimeUnit.SECONDS)
                val competing = second.get(45, TimeUnit.SECONDS)

                assertThat(winner.first).withFailMessage(winner.second).isEqualTo(200)
                when (mode) {
                    "same-key" -> assertThat(competing).isEqualTo(winner)
                    "different-key", "partial-overspend" -> assertThat(competing.first).isEqualTo(409)
                    "scope", "reassignment" -> {
                        assertThat(competing.first).withFailMessage(competing.second).isEqualTo(200)
                        assertThat(use(case).status).isIn(403, 404, 409)
                    }
                }
                assertThat(usageAccounting(case)).startsWith("17500|82500|1|1|1|1|")
            }
        } finally {
            releasePosting.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue()
        }
    }
}
