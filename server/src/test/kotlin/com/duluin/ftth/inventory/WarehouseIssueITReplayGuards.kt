package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseIssueITReplayGuards : WarehouseIssueReplayFixture() {
    @ParameterizedTest @ValueSource(strings = ["pick", "unpick", "dispatch"])
    fun `revoking only substitution override denies stored issue responses and slips`(action: String) {
        val setup = substitutedSetup()
        val picker = picker(setup)
        scope(setup, picker.second, transit(setup), 0, true)
        val path = "/api/work-orders/${setup.workOrder}/materials"
        val pick = pickBody(setup)
        val first = request("POST", "$path/pick", picker.first, pick, "sub-pick")
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(200)
        val issue = mapper.readTree(first.contentAsString)
        val body = if (action == "pick") pick else transitionBody(setup, issue)
        val key = if (action == "pick") "sub-pick" else "sub-$action"
        val original = if (action == "pick") first else request("POST", "$path/$action", picker.first, body, key)
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(200)
        overridePermission(setup, picker, false)
        assertThat(request("POST", "$path/$action", picker.first, body, key).status).isEqualTo(403)
        assertThat(request("GET", "$path/issues/${issue.path("issueId").asString()}/slip", picker.first).status).isEqualTo(403)
        overridePermission(setup, picker, true)
        assertThat(request("POST", "$path/$action", picker.first, body, key).contentAsString).isEqualTo(original.contentAsString)
    }

    @Test fun `ordinary replay does not require substitution override but dispatch requires both current location scopes`() {
        val setup = issuedSetup(serials = 1)
        val picker = picker(setup)
        val destination = transit(setup)
        val path = "/api/work-orders/${setup.workOrder}/materials"
        val body = pickBody(setup)
        val picked = request("POST", "$path/pick", picker.first, body, "normal-pick")
        assertThat(picked.status).withFailMessage(picked.contentAsString).isEqualTo(200)
        val issue = mapper.readTree(picked.contentAsString)
        val slip = "$path/issues/${issue.path("issueId").asString()}/slip"
        overridePermission(setup, picker, false)
        assertThat(request("POST", "$path/pick", picker.first, body, "normal-pick").contentAsString).isEqualTo(picked.contentAsString)
        assertThat(request("GET", slip, picker.first).status).isEqualTo(200)
        val dispatchBody = transitionBody(setup, issue)
        assertThat(request("POST", "$path/dispatch", picker.first, dispatchBody, "normal-dispatch").status).isEqualTo(404)
        scope(setup, picker.second, destination, 0, true)
        val dispatched = request("POST", "$path/dispatch", picker.first, dispatchBody, "normal-dispatch")
        assertThat(dispatched.status).withFailMessage(dispatched.contentAsString).isEqualTo(200)
        for (location in listOf(destination, setup.stock.bin)) {
            scope(setup, picker.second, location, 1, false)
            assertThat(request("POST", "$path/dispatch", picker.first, dispatchBody, "normal-dispatch").status).isEqualTo(404)
            assertThat(request("GET", slip, picker.first).status).isEqualTo(404)
            scope(setup, picker.second, location, 2, true)
            assertThat(request("POST", "$path/dispatch", picker.first, dispatchBody, "normal-dispatch").contentAsString).isEqualTo(dispatched.contentAsString)
        }
    }
}
