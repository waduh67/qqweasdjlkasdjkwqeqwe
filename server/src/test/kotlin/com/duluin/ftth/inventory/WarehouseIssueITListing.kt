package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class WarehouseIssueITListing : WarehouseIssueReplayFixture() {
    @Test fun `issue pages survive unpick repick and dispatch and apply current destination scope before counting`() {
        val setup = issuedSetup(serials = 1)
        val actor = picker(setup)
        val path = "/api/work-orders/${setup.workOrder}/materials/issues"
        val first = picked(setup)
        val firstId = first.path("issueId").asString()
        val initial = page(path, actor.first)
        assertThat(initial.path("totalElements").asInt()).isEqualTo(1)
        assertThat(initial.path("items")[0].path("receiver").path("id").asString()).isEqualTo(setup.technicianId)
        assertThat(initial.path("items")[0].path("lines")[0].path("serial").asString()).isEqualTo("SERIAL-1")
        assertThat(initial.path("items")[0].path("lines")[0].path("pickedBase").asString()).isEqualTo("1")
        assertThat(initial.path("items")[0].path("lines")[0].path("acceptedBase").asString()).isEqualTo("0")

        assertThat(issueRequest(setup, "unpick", transitionBody(setup, first)).status).isEqualTo(200)
        val second = picked(setup)
        val secondId = second.path("issueId").asString()
        assertThat(page("$path?size=1", actor.first).path("items")[0].path("issueId").asString()).isEqualTo(secondId)
        assertThat(page("$path?size=1&page=1", actor.first).path("items")[0].path("issueId").asString()).isEqualTo(firstId)
        val unpicked = page("$path?state=UNPICKED", actor.first)
        assertThat(unpicked.path("totalElements").asInt()).isEqualTo(1)
        assertThat(unpicked.path("items")[0].path("lines")[0].path("pickedBase").asString()).isEqualTo("0")

        assertThat(issueRequest(setup, "dispatch", transitionBody(setup, second)).status).isEqualTo(200)
        // Source-only access still sees the old unpicked slip, not the newly hidden dispatch.
        val hidden = page("$path?size=1", actor.first)
        assertThat(hidden.path("totalElements").asInt()).isEqualTo(1)
        assertThat(hidden.path("items")[0].path("issueId").asString()).isEqualTo(firstId)
        assertThat(page("$path?size=1&page=1", actor.first).path("items").size()).isZero()
        scope(setup, actor.second, transit(setup), 0, true)
        val before = fixture(setup.stock.token).transaction { counts() }
        val dispatched = page("$path?state=DISPATCHED", actor.first).path("items")[0]
        assertThat(dispatched.path("issueId").asString()).isEqualTo(secondId)
        assertThat(dispatched.path("revision").asLong()).isEqualTo(2)
        assertThat(dispatched.path("lines")[0].path("dispatchedBase").asString()).isEqualTo("1")
        assertThat(dispatched.path("lines")[0].path("acceptedBase").asString()).isEqualTo("0")
        for (query in listOf("size=0", "size=101", "page=-1", "page=0&page=1", "state=BOGUS", "serial=SERIAL-1")) {
            assertThat(request("GET", "$path?$query", actor.first).status).isEqualTo(400)
        }
        assertThat(request("GET", path, tenant()).status).isEqualTo(404)
        fixture(setup.stock.token).transaction { assertThat(counts()).isEqualTo(before) }
        scope(setup, actor.second, setup.stock.bin, 1, false)
        assertThat(page(path, actor.first).path("totalElements").asInt()).isZero()
    }

    @Test fun `revoked substitution override removes issue names and totals from list without changing immutable slips`() {
        val setup = substitutedSetup()
        val actor = picker(setup)
        val issue = picked(setup)
        val path = "/api/work-orders/${setup.workOrder}/materials/issues"
        assertThat(page(path, actor.first).path("totalElements").asInt()).isEqualTo(1)
        overridePermission(setup, actor, false)
        val denied = page(path, actor.first)
        assertThat(denied.path("totalElements").asInt()).isZero()
        assertThat(denied.path("items").size()).isZero()
        assertThat(request("GET", "$path/${issue.path("issueId").asString()}/slip", actor.first).status).isEqualTo(403)
        overridePermission(setup, actor, true)
        assertThat(page(path, actor.first).path("items")[0].path("issueId").asString()).isEqualTo(issue.path("issueId").asString())
        val withoutIssueRead = user(setup.stock.token, setOf("workorder.order.view", "inventory.request.view"))
        val me = mapper.readTree(request("GET", "/api/me", withoutIssueRead.first).contentAsString)
        assertThat(request("PUT", "/api/users/${withoutIssueRead.second}/access", setup.stock.token, mapper.writeValueAsString(mapOf(
            "roleIds" to me.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(setup.stock.token))))).status).isEqualTo(200)
        assertThat(request("GET", path, withoutIssueRead.first).status).isEqualTo(403)
    }

    private fun picked(setup: IssueSetup): JsonNode {
        val result = issueRequest(setup, "pick", pickBody(setup))
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        return mapper.readTree(result.contentAsString)
    }
    private fun page(path: String, token: String): JsonNode {
        val result = request("GET", path, token)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        return mapper.readTree(result.contentAsString)
    }
}
