package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.JsonNode
import java.util.UUID

abstract class WarehouseReplenishmentFixture : WarehouseReservationFixture() {
    protected val root = "/api/v1/warehouse/replenishments"

    internal fun ruleBody(fixture: WarehousePostingFixture) = """{"skuId":"${fixture.sku}","locationId":"${fixture.warehouse}",
        "baseUnit":"MM","minimumBase":"50000","maximumBase":"100000","targetBase":"100000",
        "packageMultipleBase":"25000","leadTimeDays":7}"""

    internal fun createRule(token: String, fixture: WarehousePostingFixture, body: String = ruleBody(fixture), key: String = UUID.randomUUID().toString()): JsonNode =
        command(token, "rules", body, key)

    protected fun command(token: String, path: String, body: String, key: String = UUID.randomUUID().toString()): JsonNode {
        val response = request("POST", "$root/$path", token, body, key)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }

    protected fun recompute(token: String, rule: JsonNode): JsonNode = command(token, "rules/${rule.path("id").asString()}/recompute",
        """{"expectedRevision":${rule.path("revision").asLong()}}""")

    protected fun accept(token: String, suggestion: JsonNode, key: String = UUID.randomUUID().toString()): JsonNode = command(token,
        "requests/${suggestion.path("id").asString()}/accept", acceptance(suggestion), key)

    protected fun acceptance(suggestion: JsonNode) = """{"expectedRevision":${suggestion.path("revision").asLong()},
        "expectedRuleRevision":${suggestion.path("ruleRevision").asLong()},"quantityBase":"${suggestion.path("quantityBase").asString()}"}"""

    protected fun read(token: String, path: String): JsonNode {
        val response = request("GET", "$root/$path", token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }
}
