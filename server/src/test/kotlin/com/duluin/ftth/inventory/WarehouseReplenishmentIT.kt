package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseReplenishmentIT : WarehouseReplenishmentFixture() {
    @Test fun `threshold rounds exact deficit and acceptance never posts stock`() {
        val (token, fixture) = prepare()
        receive(fixture, "60")
        reserve(demand(token, fixture, 20000, false))
        val before = fixture.transaction { scalar("SELECT count(*) FROM inventory_movement") }
        val rule = createRule(token, fixture)
        val suggestion = recompute(token, rule)
        assertThat(suggestion.path("availableBase").asString()).isEqualTo("40000")
        assertThat(suggestion.path("reservedBase").asString()).isEqualTo("20000")
        assertThat(suggestion.path("quantityBase").asString()).isEqualTo("75000")
        assertThat(recompute(token, rule).path("id")).isEqualTo(suggestion.path("id"))
        val key = UUID.randomUUID().toString()
        val body = """{"expectedRevision":0,"expectedRuleRevision":0,"quantityBase":"75000"}"""
        val accepted = request("POST", "$root/requests/${suggestion.path("id").asString()}/accept", token, body, key)
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        assertThat(request("POST", "$root/requests/${suggestion.path("id").asString()}/accept", token, body, key).contentAsString)
            .isEqualTo(accepted.contentAsString)
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_replenishment_request WHERE state='PENDING'") }).isEqualTo("1")
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_movement") }).isEqualTo(before)
        assertThat(fixture.transaction { scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE location_id='${fixture.warehouse}'") }).isEqualTo("60000")
    }

    @Test fun `restock makes acceptance stale and a later shortage gets a new window`() {
        val (token, fixture) = prepare()
        val rule = createRule(token, fixture)
        val first = recompute(token, rule)
        receive(fixture, "100")
        val response = request("POST", "$root/requests/${first.path("id").asString()}/accept", token,
            """{"expectedRevision":0,"expectedRuleRevision":0,"quantityBase":"100000"}""", "stale-stock")
        assertThat(response.status).isEqualTo(409)
        assertThat(response.contentAsString).contains("STALE_REVISION")
        assertThat(recompute(token, rule).path("state").asString()).isEqualTo("FULFILLED")
        reserve(demand(token, fixture, 80000, false))
        val second = recompute(token, rule)
        assertThat(second.path("id")).isNotEqualTo(first.path("id"))
        assertThat(second.path("quantityBase").asString()).isEqualTo("100000")
    }

    @Test fun `rules reject malformed thresholds and foreign locations`() {
        val (token, fixture) = prepare()
        val invalid = request("POST", "$root/rules", token, ruleBody(fixture).replace("\"50000\"", "\"100001\""), "bad-min")
        assertThat(invalid.status).isEqualTo(400)
        val foreign = prepare().second
        val denied = request("POST", "$root/rules", token, ruleBody(fixture).replace(fixture.warehouse.toString(), foreign.warehouse.toString()), "foreign")
        assertThat(denied.status).isEqualTo(404)
    }

}
