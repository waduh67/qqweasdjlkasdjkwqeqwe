package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.application.service.WarehouseReplenishmentScan
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseReplenishmentITCommands : WarehouseReplenishmentFixture() {
    @Test fun `rule edits archive pages and immutable accepted snapshot survive replay`() {
        val (token, fixture) = prepare()
        val body = ruleBody(fixture)
        val rule = createRule(token, fixture, body, "create")
        assertThat(createRule(token, fixture, body, "create")).isEqualTo(rule)
        assertThat(request("POST", "$root/rules", token, body.replace(":7", ":8"), "create").status).isEqualTo(409)
        val id = rule.path("id").asString()
        assertThat(read(token, "rules?size=1").path("totalElements").asLong()).isEqualTo(1)
        val suggestion = recompute(token, rule)
        val accepted = accept(token, suggestion, "accept")
        val changed = request("PUT", "$root/rules/$id", token, body.dropLast(1) + ",\"expectedRevision\":0}")
        assertThat(changed.status).isEqualTo(200)
        assertThat(accept(token, suggestion, "accept")).isEqualTo(accepted)
        assertThat(read(token, "requests/${accepted.path("id").asString()}").path("ruleSnapshot")).isEqualTo(accepted.path("ruleSnapshot"))
        assertThat(request("POST", "$root/rules/$id/archive", token, """{"expectedRevision":1}""").status).isEqualTo(409)
        command(token, "requests/${accepted.path("id").asString()}/cancel", """{"expectedRevision":${accepted.path("revision").asLong()}}""")
        val archived = command(token, "rules/$id/archive", """{"expectedRevision":1}""")
        assertThat(archived.path("active").asBoolean()).isFalse()
        assertThat(read(token, "rules/$id/history?size=2").size()).isEqualTo(2)
        assertThat(request("GET", "$root/rules?size=101", token).status).isEqualTo(400)
        assertThat(request("GET", "$root/requests?page=-1", token).status).isEqualTo(400)
        assertThatThrownBy {
            fixture.transaction { sql("UPDATE inventory_replenishment_request SET quantity_base=1,revision=revision+1 WHERE id='${accepted.path("id").asString()}'") }
        }.hasRootCauseInstanceOf(java.sql.SQLException::class.java)
    }

    @Test fun `malformed quantity unit revision and authority input never create a rule`() {
        val (token, fixture) = prepare()
        val original = ruleBody(fixture)
        val malformed = listOf(
            original.replace("\"50000\"", "50000"), original.replace("\"50000\"", "\"0.001\""),
            original.replace("\"50000\"", "\"9223372036854775808\""), original.replace("\"50000\"", "\"-1\""),
            original.replace("\"25000\"", "\"0\""), original.replace(":7", ":-1"), original.replace(":7", ":3651"),
            original.replace("\"MM\"", "\"EA\""), original.replace("\"MM\"", "0"), original.replace(":7", ":7.1"),
            original.dropLast(1) + ",\"actorId\":\"${fixture.actor}\"}", original.dropLast(1) + ",\"leadTimeDays\":1}",
        )
        malformed.forEach { body ->
            val result = request("POST", "$root/rules", token, body)
            assertThat(result.status).withFailMessage("%s: %s", body, result.contentAsString).isEqualTo(400)
        }
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_replenishment_rule") }).isEqualTo("0")
        val viewer = user(token, setOf("inventory.request.view"))
        assertThat(request("POST", "$root/rules", viewer.first, original).status).isEqualTo(403)
        assertThat(request("POST", "$root/rules", null, original).status).isEqualTo(401)
    }

    @Test fun `scheduler repeated batches and concurrent accept preserve one pending request`() {
        val (token, fixture) = prepare()
        val rule = createRule(token, fixture)
        repeat(4) { TenantContext.runAs(fixture.tenant) { context.getBean(WarehouseReplenishmentScan::class.java).batch() } }
        val suggestion = read(token, "requests").path("items")[0]
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val attempts = (1..2).map { executor.submit<Int> {
                check(start.await(10, TimeUnit.SECONDS))
                request("POST", "$root/requests/${suggestion.path("id").asString()}/accept", token, acceptance(suggestion), "race-$it").status
            } }
            start.countDown()
            assertThat(attempts.map { it.get(45, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(200, 409)
        } finally { executor.shutdownNow() }
        assertThat(recompute(token, rule).path("id")).isEqualTo(suggestion.path("id"))
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_replenishment_request") }).isEqualTo("1")
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_movement") }).isEqualTo("0")
    }

    @Test fun `current scope and role revocation deny both reads and stored replay`() {
        val (token, fixture) = prepare()
        val rule = createRule(token, fixture, key = "scoped")
        val user = mapper.readTree(request("GET", "/api/me", token).contentAsString).path("id").asString()
        val revoke = request("PUT", "/api/v1/warehouse/settings/scopes/$user/${fixture.warehouse}", token,
            """{"expectedRevision":0,"active":false}""")
        assertThat(revoke.status).withFailMessage(revoke.contentAsString).isEqualTo(200)
        assertThat(request("POST", "$root/rules", token, ruleBody(fixture), "scoped").status).isEqualTo(404)
        assertThat(request("GET", "$root/rules/${rule.path("id").asString()}", token).status).isEqualTo(404)
        assertThat(read(token, "rules").path("items").size()).isZero()
    }
}
