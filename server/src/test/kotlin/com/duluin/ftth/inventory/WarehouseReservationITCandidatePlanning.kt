package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseScopePersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseReservationITCandidatePlanning : WarehouseReservationFixture() {
    @Test fun `same SKU lines plan against pending deductions without false shortage or overcommit`() {
        val (token, fixture) = prepare()
        repeat(3) { receive(fixture, "10") }
        val demand = demand(token, fixture, 15000, continuous = false, lineCount = 2)
        val result = reserve(demand)
        assertThat(result.path("state").asString()).isEqualTo("RESERVED")
        assertThat(result.path("lines").asSequence().map { it.path("backorderBase").asString() }.toList()).containsExactly("0", "0")
        assertThat(allocations(demand).size()).isEqualTo(4)
        assertThat(fixture.transaction { scalar("SELECT sum(reserved_unpicked_base) FROM inventory_reservation WHERE state='OPEN'") }).isEqualTo("30000")
    }
    @Test fun `selection applies warehouse scope before the first adequate candidate limit`() {
        val (token, fixture) = prepare()
        receive(fixture, "10")
        val later = receive(fixture, "10")
        val location = UUID.fromString(create("locations", token, """{"code":"SECOND","name":"Second warehouse","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString())
        fixture.transaction { post(move(later, later.copy(locationId = location, custodianId = location), com.duluin.ftth.inventory.domain.model.StockQuantity.metres("10"))) }
        val actor = UUID.fromString(mapper.readTree(request("GET", "/api/me", token).contentAsString).path("id").asString())
        authenticated(token, fixture) { context.getBean(WarehouseScopePersistence::class.java).replace(actor, setOf(location), 0) }
        val demand = demand(token, fixture, 10000)
        assertThat(reserve(demand).path("state").asString()).isEqualTo("RESERVED")
        assertThat(allocations(demand).single().path("stockIdentityId").asString()).isEqualTo(later.stockIdentityId.toString())
    }
    @Test fun `demand exceeding serial command cap is rejected explicitly without partial success`() {
        val (token, fixture) = prepare()
        val demand = demand(token, fixture, 2001, serial = true)
        val before = fixture.transaction { counts() }
        val result = request("POST", "/api/v1/warehouse/material-requests/${demand.document}/reserve", token,
            """{"expectedRevision":1,"workOrderRevision":0,"planRevision":1}""")
        assertThat(result.status).isEqualTo(400)
        assertThat(result.contentAsString).contains("MALFORMED_REQUEST")
        assertThat(fixture.transaction { counts() }).isEqualTo(before)
    }
}
