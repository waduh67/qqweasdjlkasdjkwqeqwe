package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkOrderMaterialReceiptITConcurrency : MaterialReceiptFixture() {
    @ParameterizedTest @ValueSource(strings = ["same-key", "different-key", "scope", "reassignment"])
    fun `competing acknowledgements preserve single custody and current authority`(mode: String) {
        val case = receiptCase()
        val replacement = if (mode == "reassignment") technician(case.stock.token).second else null
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val first = executor.submit<Pair<Int, String>> {
                check(start.await(10, TimeUnit.SECONDS))
                val result = acknowledge(case)
                result.status to result.contentAsString
            }
            val second = executor.submit<Pair<Int, String>> {
                check(start.await(10, TimeUnit.SECONDS))
                val result = when (mode) {
                    "scope" -> request("PUT", "/api/v1/warehouse/settings/scopes/${case.receiver.second}/${case.transit}", case.stock.token,
                        """{"expectedRevision":1,"active":false}""")
                    "reassignment" -> request("POST", "/api/work-orders/${case.workOrder}/assign", case.stock.token,
                        """{"technicianIds":["$replacement"]}""")
                    "same-key" -> acknowledge(case)
                    "different-key" -> acknowledge(case, key = UUID.randomUUID().toString())
                    else -> error("Unknown test mode")
                }
                result.status to result.contentAsString
            }

            start.countDown()
            val result = first.get(45, TimeUnit.SECONDS)
            val competing = second.get(45, TimeUnit.SECONDS)

            when (mode) {
                "same-key" -> {
                    assertThat(result.first).withFailMessage(result.second).isEqualTo(200)
                    assertThat(competing).isEqualTo(result)
                }
                "different-key" -> assertThat(listOf(result.first, competing.first)).containsExactlyInAnyOrder(200, 409)
                "scope", "reassignment" -> {
                    assertThat(competing.first).withFailMessage(competing.second).isEqualTo(200)
                    assertThat(result.first).isIn(200, 403, 404, 409)
                    assertThat(acknowledge(case).status).isIn(403, 404, 409)
                }
            }
            val applied = result.first == 200 || (mode in setOf("same-key", "different-key") && competing.first == 200)
            assertThat(accounting(case)).startsWith(if (applied) "PART_RECEIVED|3|40000|60000|1|" else "DISPATCHED|2|100000|0|0|")
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue()
        }
    }
}
