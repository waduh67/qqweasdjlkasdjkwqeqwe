package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseScopePersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.time.Instant

class WarehouseReservationITAuthority : WarehouseReservationFixture() {
    @Test fun `FIFO default and explicit override require current permission and audited reason`() {
        val (admin, fixture) = prepare()
        val oldest = receive(fixture, "10")
        val newest = receive(fixture, "10")
        val first = demand(admin, fixture, 10000)
        reserve(first)
        assertThat(allocations(first).single().path("stockIdentityId").asString()).isEqualTo(oldest.stockIdentityId.toString())
        val second = demand(admin, fixture, 10000)
        val (limited, userId) = user(admin, setOf("inventory.request.manage", "inventory.request.view"))
        val roles = mapper.readTree(request("GET", "/api/me", limited).contentAsString).path("roleIds")
        assertThat(request("PUT", "/api/users/$userId/access", admin, """{"roleIds":$roles,"areaIds":["${area(admin)}"]}""").status).isEqualTo(200)
        authenticated(admin, fixture) { context.getBean(WarehouseScopePersistence::class.java).replace(UUID.fromString(userId), setOf(fixture.warehouse), 0) }
        val selected = """, "lines":[{"demandLineId":"${second.line}","stockIdentityId":"${newest.stockIdentityId}"}]"""
        val body = """{"expectedRevision":1,"workOrderRevision":0,"planRevision":1$selected,"reason":"Job-specific serial selection"}"""
        val path = "/api/v1/warehouse/material-requests/${second.document}/reserve"
        assertThat(request("POST", path, limited, body).status).isEqualTo(403)
        assertThat(request("POST", path, admin, body.replace(",\"reason\":\"Job-specific serial selection\"", "")).status).isEqualTo(400)
        call(second, "reserve", body)
        assertThat(allocations(second).single().path("stockIdentityId").asString()).isEqualTo(newest.stockIdentityId.toString())
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_movement WHERE reason='Job-specific serial selection'") }).isEqualTo("1")
    }
    @Test fun `replay cannot reveal response to another actor or after permission revocation`() {
        val (admin, fixture) = prepare()
        receive(fixture, "10")
        val demand = demand(admin, fixture, 10000)
        val (token, userId) = user(admin, setOf("inventory.request.manage", "inventory.request.view"))
        val roles = mapper.readTree(request("GET", "/api/me", token).contentAsString).path("roleIds")
        request("PUT", "/api/users/$userId/access", admin, """{"roleIds":$roles,"areaIds":["${area(admin)}"]}""")
        authenticated(admin, fixture) { context.getBean(WarehouseScopePersistence::class.java).replace(UUID.fromString(userId), setOf(fixture.warehouse), 0) }
        val path = "/api/v1/warehouse/material-requests/${demand.document}/reserve"
        val body = """{"expectedRevision":1,"workOrderRevision":0,"planRevision":1}"""
        val original = request("POST", path, token, body, "lost-response")
        assertThat(original.status).isEqualTo(200)
        assertThat(request("POST", path, token, body, "lost-response").contentAsString).isEqualTo(original.contentAsString)
        assertThat(request("POST", path, admin, body, "lost-response").status).isEqualTo(403)
        val before = fixture.transaction { counts() }
        assertThat(request("PUT", "/api/users/$userId/access", admin, """{"roleIds":[],"areaIds":[]}""").status).isEqualTo(200)
        assertThat(request("POST", path, token, body, "lost-response").status).isEqualTo(403)
        assertThat(fixture.transaction { counts() }).isEqualTo(before)
    }
    @Test fun `foreign reservations overreservation stale revisions and forged authority fail closed`() {
        val (token, fixture) = prepare()
        receive(fixture, "60")
        val source = demand(token, fixture, 60000)
        val foreignJob = demand(token, fixture, 60000)
        reserve(source)
        val row = allocations(source).single()
        val before = fixture.transaction { counts() }
        assertThat(request("POST", "/api/v1/warehouse/material-requests/${foreignJob.document}/release", token, mutation(1, row, "60000")).status).isEqualTo(409)
        assertThat(request("POST", "/api/v1/warehouse/material-requests/${source.document}/release", token, mutation(2, row, "60001")).status).isEqualTo(409)
        val path = "/api/v1/warehouse/material-requests/${source.document}/reserve"
        assertThat(request("POST", path, token, """{"expectedRevision":1,"workOrderRevision":0,"planRevision":1}""").status).isEqualTo(409)
        assertThat(request("POST", path, token, """{"expectedRevision":2,"workOrderRevision":0,"planRevision":1,"actorId":"${UUID.randomUUID()}"}""").status).isEqualTo(400)
        assertThat(request("POST", path, tenant(), """{"expectedRevision":2,"workOrderRevision":0,"planRevision":1}""").status).isEqualTo(404)
        assertThat(fixture.transaction { counts() }).isEqualTo(before)
    }
    @Test fun `dispatcher extension changes only expiry and records an immutable operation`() {
        val (token, fixture) = prepare()
        receive(fixture, "10")
        val demand = demand(token, fixture, 10000)
        reserve(demand)
        val row = allocations(demand).single()
        val old = Instant.parse(row.path("expiresAt").asString())
        val expected = old.plusSeconds(86400)
        val legs = fixture.transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }
        call(demand, "extend", mutation(2, row, "10000", """, "expiresAt":"$expected""""))
        assertThat(Instant.parse(allocations(demand).single().path("expiresAt").asString())).isEqualTo(expected)
        assertThat(available(demand)).isEqualTo("0")
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }).isEqualTo(legs)
    }
}
