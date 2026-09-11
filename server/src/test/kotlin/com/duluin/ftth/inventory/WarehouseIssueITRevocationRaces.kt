package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseIssueITRevocationRaces : WarehouseIssueFixture() {
    @ParameterizedTest
    @ValueSource(strings = ["SCOPE", "REASSIGN", "CANCEL"])
    fun `pick racing current authority changes either commits before them or rejects without writes`(mode: String) {
        val setup = issuedSetup(serials = 1)
        val body = pickBody(setup)
        val path = "/api/work-orders/${setup.workOrder}/materials/pick"
        var actor = setup.stock.token
        val method: String
        val changePath: String
        val changeBody: String
        if (mode == "SCOPE") {
            val picker = user(setup.stock.token, setOf("workorder.order.view", "inventory.issue.manage", "inventory.issue.view",
                "inventory.request.manage", "inventory.request.view", "inventory.sku.view"))
            val me = mapper.readTree(request("GET", "/api/me", picker.first).contentAsString)
            assertThat(request("PUT", "/api/users/${picker.second}/access", setup.stock.token, mapper.writeValueAsString(mapOf(
                "roleIds" to me.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(setup.stock.token))))).status).isEqualTo(200)
            changePath = "/api/v1/warehouse/settings/scopes/${picker.second}/${setup.stock.bin}"
            assertThat(request("PUT", changePath, setup.stock.token, """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
            actor = picker.first
            method = "PUT"
            changeBody = """{"expectedRevision":1,"active":false}"""
        } else if (mode == "REASSIGN") {
            val replacement = technician(setup.stock.token)
            method = "POST"
            changePath = "/api/work-orders/${setup.workOrder}/assign"
            changeBody = """{"technicianIds":["${replacement.second}"]}"""
        } else {
            method = "POST"
            changePath = "/api/work-orders/${setup.workOrder}/cancel"
            changeBody = """{"reason":"Concurrent cancellation"}"""
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val gate = CountDownLatch(1)
            val pick = executor.submit<Pair<Int, String>> {
                check(gate.await(10, TimeUnit.SECONDS))
                val response = request("POST", path, actor, body, "authority-race")
                response.status to response.contentAsString
            }
            val change = executor.submit<Int> {
                check(gate.await(10, TimeUnit.SECONDS))
                request(method, changePath, setup.stock.token, changeBody).status
            }
            gate.countDown()
            val result = pick.get(40, TimeUnit.SECONDS)
            assertThat(change.get(40, TimeUnit.SECONDS)).isEqualTo(200)
            assertThat(result.first).withFailMessage("$mode: ${result.second}").isIn(200, 403, 404, 409)
            val replay = request("POST", path, actor, body, "authority-race")
            assertThat(replay.status).withFailMessage(replay.contentAsString).isIn(403, 404, 409)
            fixture(setup.stock.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_document WHERE kind='ISSUE'")).isEqualTo(if (result.first == 200) "1" else "0")
                assertThat(scalar("SELECT sum(reserved_picked_base) FROM inventory_reservation")).isEqualTo(if (result.first == 200) "1" else "0")
                assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='ISSUE'")).isEqualTo("0")
                assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE location_id='${setup.stock.bin}'")).isEqualTo("1")
            }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue() }
    }
}
