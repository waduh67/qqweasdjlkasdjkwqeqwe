package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkOrderMaterialsITConcurrency : MaterialWorkflowFixture() {
    @Test fun `concurrent replacements have one winner and same key submissions create one demand`() {
        val setup = setupReceipt()
        val token = setup.token
        val id = workOrder(token)
        val body = plan(token, id, "[${line(setup.cable)}]")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val gate = CountDownLatch(1)
            val calls = (1..2).map { index -> executor.submit<Pair<Int, String>> {
                check(gate.await(10, TimeUnit.SECONDS))
                val result = request("PUT", "/api/work-orders/$id/materials/plan", token, body, "concurrent-$index")
                result.status to result.contentAsString
            } }
            gate.countDown()
            assertThat(calls.map { it.get(40, TimeUnit.SECONDS).first }).containsExactlyInAnyOrder(200, 409)
            val submit = command(token, id, 1)
            val submissions = (1..2).map { executor.submit<Pair<Int, String>> {
                val result = request("POST", "/api/work-orders/$id/materials/submit-request", token, submit, "one-demand")
                result.status to result.contentAsString
            } }.map { it.get(40, TimeUnit.SECONDS) }
            assertThat(submissions.map { it.first }).containsOnly(200)
            assertThat(submissions[0].second).isEqualTo(submissions[1].second)
            fixture(token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_material_plan")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_document WHERE kind='DEMAND'")).isEqualTo("1")
            }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue() }
    }
    @Test fun `WO reassignment invalidates the planned owner revision without transferring material`() {
        val setup = setupReceipt()
        val id = workOrder(setup.token)
        val technician = technician(setup.token)
        assign(setup.token, id, technician.second)
        val before = plan(setup.token, id, "[${line(setup.cable)}]")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val gate = CountDownLatch(1)
            val replacement = executor.submit<Int> {
                check(gate.await(10, TimeUnit.SECONDS))
                request("PUT", "/api/work-orders/$id/materials/plan", setup.token, before).status
            }
            val other = technician(setup.token)
            val assignment = executor.submit {
                check(gate.await(10, TimeUnit.SECONDS))
                assign(setup.token, id, other.second)
            }
            gate.countDown()
            val result = replacement.get(40, TimeUnit.SECONDS)
            assignment.get(40, TimeUnit.SECONDS)
            assertThat(result).isIn(200, 409)
            if (result == 200) assertThat(request("POST", "/api/work-orders/$id/materials/submit-request", setup.token,
                command(setup.token, id, 1)).status).isEqualTo(409)
            fixture(setup.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_document WHERE kind='DEMAND'")).isEqualTo("0") }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue() }
    }
}
