package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class WarehouseReservationITSupplyProjection : WarehouseReservationFixture() {
    @Test fun `fulfillment projection includes durable line quantities and demand state`() {
        val (token, fixture) = prepare()
        receive(fixture, "60")
        val demand = demand(token, fixture, 100000, false)
        reserve(demand)
        val allocation = authenticated(token, fixture) { context.getBean(InventoryApi::class.java).fulfillmentAllocations(demand.workOrder).single() }
        assertThat(allocation.quantity).isNull()
        assertThat(allocation.customerId).isNull()
        val supply = mapper.readTree(mapper.writeValueAsString(allocation)).path("reservation").path("demandSupply")
        assertThat(supply.path("requestedBase").asString()).isEqualTo("100000")
        assertThat(supply.path("reservedUnpickedBase").asString()).isEqualTo("60000")
        assertThat(supply.path("reservedPickedBase").asString()).isEqualTo("0")
        assertThat(supply.path("totalReservedBase").asString()).isEqualTo("60000")
        assertThat(supply.path("backorderBase").asString()).isEqualTo("40000")
        assertThat(supply.path("demandState").asString()).isEqualTo("PART_RESERVED")
        assertThat(supply.path("documentRevision").asLong()).isEqualTo(2)
        assertThat(supply.path("baseUnit").asString()).isEqualTo("MM")
        assertThat(supply.path("planRevision").asLong()).isEqualTo(1)
        assertThat(supply.path("snapshotId").asString()).isNotBlank()
        assertThat(supply.path("operationId").asString()).isNotBlank()

        val original = allocations(demand).single()
        val pick = mutation(2, original, "20000")
        call(demand, "pick", pick, "projection-pick")
        val picked = allocations(demand).single()
        assertSupply(picked, "40000", "20000", "60000", "40000", "PART_RESERVED", 3)
        call(demand, "pick", pick, "projection-pick")
        assertThat(allocations(demand).single()).isEqualTo(picked)
        call(demand, "unpick", mutation(3, picked, "20000"))
        call(demand, "release", mutation(4, allocations(demand).single(), "60000"))
        val released = allocations(demand).single()
        assertSupply(released, "0", "0", "0", "100000", "SUBMITTED", 5)
        assertThat(released.path("demandSupply").path("snapshotId").asString()).isNotEqualTo(supply.path("snapshotId").asString())
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_demand_supply_snapshot WHERE document_line_id='${demand.line}'") }).isEqualTo("4")
    }

    @Test fun `every allocation carries complete line totals including other identities and released links`() {
        val (token, fixture) = prepare()
        receive(fixture, "30"); receive(fixture, "30")
        val demand = demand(token, fixture, 100000, false)
        reserve(demand)
        val initial = allocations(demand)
        assertThat(initial.size()).isEqualTo(2)
        initial.asSequence().forEach { assertSupply(it, "60000", "0", "60000", "40000", "PART_RESERVED", 2) }
        call(demand, "release", mutation(2, initial[0], "30000"))
        val after = allocations(demand)
        assertThat(after.size()).isEqualTo(2)
        after.asSequence().forEach { assertSupply(it, "30000", "0", "30000", "70000", "PART_RESERVED", 3) }
        assertThat(after.asSequence().count { it.path("state").asString() == "RELEASED" }).isEqualTo(1)
    }

    @Test fun `expiry projects exact picked backorder and rejects a missing current supply snapshot`() {
        val (token, fixture) = prepare()
        receive(fixture, "60")
        val demand = demand(token, fixture, 100000, false)
        reserve(demand)
        call(demand, "pick", mutation(2, allocations(demand).single(), "20000"))
        fixture.transaction { sql("UPDATE inventory_reservation SET submitted_at=clock_timestamp()-interval '2 days',expires_at=clock_timestamp()-interval '1 second',revision=revision+1") }
        assertThat(com.duluin.ftth.common.tenant.TenantContext.runAs(fixture.tenant) {
            context.getBean(com.duluin.ftth.inventory.application.service.WarehouseReservationExpiry::class.java).expireOne()
        }).isTrue()
        assertSupply(allocations(demand).single(), "0", "20000", "20000", "80000", "PART_RESERVED", 4)
        fixture.transaction { sql("UPDATE inventory_document SET revision=revision+1 WHERE id='${demand.document}'") }
        val missing = request("GET", "/api/v1/warehouse/material-requests/allocations/${demand.workOrder}", token)
        assertThat(missing.status).isEqualTo(409)
        assertThat(missing.contentAsString).contains("SOURCE_NOT_VERIFIED")
    }

    private fun assertSupply(allocation: JsonNode, unpicked: String, picked: String, total: String, backorder: String, state: String, revision: Long) {
        val supply = allocation.path("demandSupply")
        assertThat(supply.path("requestedBase").asString()).isEqualTo("100000")
        assertThat(supply.path("reservedUnpickedBase").asString()).isEqualTo(unpicked)
        assertThat(supply.path("reservedPickedBase").asString()).isEqualTo(picked)
        assertThat(supply.path("totalReservedBase").asString()).isEqualTo(total)
        assertThat(supply.path("backorderBase").asString()).isEqualTo(backorder)
        assertThat(supply.path("demandState").asString()).isEqualTo(state)
        assertThat(supply.path("documentRevision").asLong()).isEqualTo(revision)
    }
}
