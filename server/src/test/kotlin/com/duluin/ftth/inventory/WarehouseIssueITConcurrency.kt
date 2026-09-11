package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseIssueITConcurrency : WarehouseIssueFixture() {
    @Test fun `two WO reservations on one parent retain both encumbrances while only one concurrent cut wins`() {
        val first = issuedSetup()
        val workOrder = workOrder(first.stock.token)
        assign(first.stock.token, workOrder, first.technicianId)
        putPlan(first.stock.token, workOrder, plan(first.stock.token, workOrder, "[${line(first.stock.cable)}]"))
        action(first.stock.token, workOrder, "submit-request", command(first.stock.token, workOrder, 1))
        action(first.stock.token, workOrder, "reserve", command(first.stock.token, workOrder, 1))
        val second = IssueSetup(first.stock, workOrder, first.technicianId)
        val inputs = listOf(first to pickBody(first), second to pickBody(second))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val gate = CountDownLatch(1)
            val calls = inputs.map { (setup, body) -> executor.submit<Int> {
                check(gate.await(10, TimeUnit.SECONDS))
                issueRequest(setup, "pick", body).status
            } }
            gate.countDown()
            assertThat(calls.map { it.get(40, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(200, 409)
            fixture(first.stock.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_reservation")).isEqualTo("2")
                assertThat(scalar("SELECT sum(reserved_unpicked_base+reserved_picked_base) FROM inventory_reservation")).isEqualTo("200000")
                assertThat(scalar("SELECT count(*) FROM inventory_segment WHERE state='SPLIT'")).isEqualTo("1")
            }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue() }
    }
    @Test fun `same parent and last serial have one winner under different keys and replay identical under same key`() {
        for (serials in listOf(0, 1)) {
            val setup = issuedSetup(serials)
            val body = pickBody(setup)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val gate = CountDownLatch(1)
                val futures = (1..2).map { number -> executor.submit<Pair<Int, String>> {
                    check(gate.await(10, TimeUnit.SECONDS))
                    val response = issueRequest(setup, "pick", body, "race-$number")
                    response.status to response.contentAsString
                } }
                gate.countDown()
                val results = futures.map { it.get(40, TimeUnit.SECONDS) }
                assertThat(results.map { it.first }).containsExactlyInAnyOrder(200, 409)
                val issue = mapper.readTree(results.single { it.first == 200 }.second)
                val dispatch = transitionBody(setup, issue)
                val replies = (1..2).map { executor.submit<Pair<Int, String>> {
                    val response = issueRequest(setup, "dispatch", dispatch, "same-dispatch")
                    response.status to response.contentAsString
                } }.map { it.get(40, TimeUnit.SECONDS) }
                assertThat(replies.map { it.first }).containsOnly(200)
                assertThat(replies[0].second).isEqualTo(replies[1].second)
                fixture(setup.stock.token).transaction {
                    assertThat(scalar("SELECT count(*) FROM inventory_document WHERE kind='ISSUE'")).isEqualTo("1")
                    assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='ISSUE'")).isEqualTo("1")
                    assertThat(scalar("SELECT count(*) FROM inventory_segment WHERE state='SPLIT'")).isEqualTo(if (serials == 0) "1" else "0")
                }
            } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue() }
        }
    }

    @Test fun `current reassignment invalidates a picked issue without moving stock and permits explicit unpick`() {
        val setup = issuedSetup(serials = 1)
        val picked = issueRequest(setup, "pick", pickBody(setup))
        assertThat(picked.status).withFailMessage(picked.contentAsString).isEqualTo(200)
        val issue = mapper.readTree(picked.contentAsString)
        val replacement = technician(setup.stock.token)
        assign(setup.stock.token, setup.workOrder, replacement.second)
        val body = transitionBody(setup, issue)
        val denied = issueRequest(setup, "dispatch", body)
        assertThat(denied.status).withFailMessage(denied.contentAsString).isEqualTo(409)
        val unpicked = issueRequest(setup, "unpick", body)
        assertThat(unpicked.status).withFailMessage(unpicked.contentAsString).isEqualTo(200)
        fixture(setup.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='ISSUE'")).isEqualTo("0")
            assertThat(scalar("SELECT sum(reserved_picked_base) FROM inventory_reservation")).isEqualTo("0")
        }
    }

    @Test fun `scope revocation denies pick replay using the original JWT`() {
        val setup = issuedSetup(serials = 1)
        val picker = user(setup.stock.token, setOf("workorder.order.view", "inventory.issue.manage", "inventory.issue.view",
            "inventory.request.manage", "inventory.request.view", "inventory.sku.view"))
        val me = mapper.readTree(request("GET", "/api/me", picker.first).contentAsString)
        assertThat(request("PUT", "/api/users/${picker.second}/access", setup.stock.token, mapper.writeValueAsString(mapOf(
            "roleIds" to me.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(setup.stock.token))))).status).isEqualTo(200)
        val scope = "/api/v1/warehouse/settings/scopes/${picker.second}/${setup.stock.bin}"
        assertThat(request("PUT", scope, setup.stock.token, """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val body = pickBody(setup)
        val path = "/api/work-orders/${setup.workOrder}/materials/pick"
        val picked = request("POST", path, picker.first, body, "scope-pick")
        assertThat(picked.status).withFailMessage(picked.contentAsString).isEqualTo(200)
        assertThat(request("PUT", scope, setup.stock.token, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        val denied = request("POST", path, picker.first, body, "scope-pick")
        assertThat(denied.status).withFailMessage(denied.contentAsString).isEqualTo(404)
        assertThat(denied.contentAsString).isNotEqualTo(picked.contentAsString)
    }
}
