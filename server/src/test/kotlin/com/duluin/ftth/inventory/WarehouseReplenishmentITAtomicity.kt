package com.duluin.ftth.inventory

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.domain.model.StockQuantity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseReplenishmentITAtomicity : WarehouseReplenishmentFixture() {
    @Test fun `connection loss rolls acceptance and original response back together`() {
        val (token, fixture) = prepare()
        val suggestion = recompute(token, createRule(token, fixture))
        val id = UUID.fromString(suggestion.path("id").asString())
        val service = context.getBean(WarehouseReplenishmentApi::class.java)
        assertThatThrownBy {
            authenticated(token, fixture) {
                fixture.transaction {
                    service.accept(id, ReplenishmentAcceptance(0, 0, "100000"), "dropped")
                    scalar("SELECT pg_terminate_backend(pg_backend_pid())")
                }
            }
        }.isInstanceOf(Exception::class.java)
        val after = read(token, "requests/$id")
        assertThat(after.path("acceptedAt").isNull).isTrue()
        assertThat(after.path("revision").asLong()).isZero()
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_replenishment_operation WHERE operation_key='dropped'") }).isEqualTo("0")
        val committed = accept(token, suggestion, "dropped")
        assertThat(accept(token, suggestion, "dropped")).isEqualTo(committed)
    }

    @Test fun `acceptance waits for in-flight restock and evaluates committed current position`() {
        val (token, fixture) = prepare()
        val suggestion = recompute(token, createRule(token, fixture))
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit {
                fixture.transaction {
                    context.getBean(CurrentAuthorityApi::class.java).lockForChange().assertHeld()
                    receipt(StockQuantity.metres("100"))
                    held.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
            }
            check(held.await(15, TimeUnit.SECONDS))
            val accepting = executor.submit<Int> {
                request("POST", "$root/requests/${suggestion.path("id").asString()}/accept", token, acceptance(suggestion)).status
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var waiting = false
            while (!waiting && System.nanoTime() < deadline) {
                waiting = fixture.transaction {
                    scalar("SELECT EXISTS(SELECT FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND usename=current_user AND wait_event_type='Lock' AND query LIKE '%iam_authorization_epoch%')")
                } == "t"
                Thread.yield()
            }
            assertThat(waiting).isTrue()
            release.countDown()
            writer.get(15, TimeUnit.SECONDS)
            assertThat(accepting.get(20, TimeUnit.SECONDS)).isEqualTo(409)
            assertThat(read(token, "requests/${suggestion.path("id").asString()}").path("acceptedAt").isNull).isTrue()
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `new actor and revoked role cannot recover another authorized original response`() {
        val (token, fixture) = prepare()
        val body = ruleBody(fixture)
        createRule(token, fixture, body, "private")
        val other = user(token, setOf("inventory.request.manage", "inventory.request.view"))
        assertThat(request("POST", "$root/rules", other.first, body, "private").status).isEqualTo(403)
        val id = mapper.readTree(request("GET", "/api/me", token).contentAsString).path("id").asString()
        val removed = request("PUT", "/api/users/$id/access", token, """{"roleIds":[],"areaIds":["${area(token)}"]}""")
        assertThat(removed.status).withFailMessage(removed.contentAsString).isEqualTo(200)
        assertThat(request("POST", "$root/rules", token, body, "private").status).isEqualTo(403)
    }
}
