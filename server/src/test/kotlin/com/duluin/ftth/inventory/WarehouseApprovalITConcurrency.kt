package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseApprovalITConcurrency : WarehouseApprovalHttpFixture() {
    @Test fun `concurrent final decisions execute only one owner posting and one receipt`() {
        val case = pending()
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val calls = (1..2).map { pool.submit<Int> { check(gate.await(10, TimeUnit.SECONDS)); decide(case).status } }
            gate.countDown()
            assertThat(calls.map { it.get(30, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(200, 409)
        }
        counts(case, 1, 1)
    }
    @Test fun `concurrent same key decisions replay exactly after lock winner commits`() {
        val case = pending()
        val key = java.util.UUID.randomUUID().toString()
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val calls = (1..2).map { pool.submit<String> {
                check(gate.await(10, TimeUnit.SECONDS))
                val response = decide(case, key = key)
                assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
                response.contentAsString
            } }
            gate.countDown()
            assertThat(calls[0].get(30, TimeUnit.SECONDS)).isEqualTo(calls[1].get(30, TimeUnit.SECONDS))
        }
        counts(case, 1, 1)
    }
    @Test fun `independent tiers forbid reused actor and concurrent next tier has one winner`() {
        val setup = setupReceipt()
        val first = approver(setup.token, listOf(setup.source, setup.inspection))
        val second = approver(setup.token, listOf(setup.source, setup.inspection))
        configure(setup.token, mapper.writeValueAsString(mapOf("expectedRevision" to 0, "currency" to "IDR", "expiryHours" to 24,
            "warehouseIds" to listOf(setup.inspection), "rules" to listOf(mapOf("operation" to "RECEIPT", "tiers" to listOf(
                mapOf("minimumMinor" to "1", "userIds" to listOf(first.second), "roleIds" to emptyList<String>()),
                mapOf("minimumMinor" to "100", "userIds" to listOf(first.second, second.second), "roleIds" to emptyList<String>())))))))
        val case = submit(setup, first, draft(setup, costLine(setup)).path("id").asString())
        val partial = decide(case)
        assertThat(partial.status).withFailMessage(partial.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(partial.contentAsString).path("status").asString()).isEqualTo("PENDING")
        assertThat(decide(case, revision = 1).status).isEqualTo(403)
        counts(case, 1, 0)
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val calls = (1..2).map { pool.submit<Int> { check(gate.await(10, TimeUnit.SECONDS)); decide(case, second.first, 1).status } }
            gate.countDown()
            assertThat(calls.map { it.get(30, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(200, 409)
        }
        counts(case, 2, 1)
    }
}
