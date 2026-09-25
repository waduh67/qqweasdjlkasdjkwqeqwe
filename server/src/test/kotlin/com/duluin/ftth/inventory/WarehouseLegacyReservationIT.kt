package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode

class WarehouseLegacyReservationIT : WarehouseIssueFixture() {
    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `legacy reservation projects a physical serialized allocation until release or dispatch`(dispatch: Boolean) {
        val setup = issuedSetup(serials = 1)
        val admin = setup.stock.token
        val initial = reservations(admin)
        assertThat(initial.size()).isEqualTo(1)
        val row = initial.single()
        assertThat(row.propertyNames()).containsExactlyInAnyOrder("assetId", "skuId", "locationId", "custodianId")
        assertThat(row.path("skuId").asString()).isEqualTo(setup.stock.onu)
        assertThat(row.path("locationId").asString()).isEqualTo(setup.stock.bin)
        val physical = mapper.readTree(request("GET", "/api/inventory/serialized/${row.path("assetId").asString()}", admin).contentAsString)
        assertThat(physical.path("status").asString()).isEqualTo("AVAILABLE")
        assertThat(row.path("custodianId")).isEqualTo(physical.path("custodyOwnerId"))
        val before = fixture(admin).transaction { counts() }
        assertThat(reservations(admin)).isEqualTo(initial)
        assertThat(fixture(admin).transaction { counts() }).isEqualTo(before)

        val picked = issueRequest(setup, "pick", pickBody(setup))
        assertThat(picked.status).withFailMessage(picked.contentAsString).isEqualTo(200)
        val issue = mapper.readTree(picked.contentAsString)
        assertThat(reservations(admin)).isEqualTo(initial)
        if (dispatch) {
            val body = transitionBody(setup, issue)
            val result = issueRequest(setup, "dispatch", body, "legacy-dispatch")
            assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
            assertThat(issueRequest(setup, "dispatch", body, "legacy-dispatch").contentAsString).isEqualTo(result.contentAsString)
        } else {
            val result = issueRequest(setup, "unpick", transitionBody(setup, issue))
            assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
            assertThat(reservations(admin)).isEqualTo(initial)
            action(admin, setup.workOrder, "release", command(admin, setup.workOrder, 1, "Release after unpick"))
        }
        assertThat(reservations(admin).size()).isZero()
    }

    @Test fun `legacy device reservations never coerce measured cable allocations into device rows`() {
        val setup = issuedSetup()
        assertThat(reservations(setup.stock.token).size()).isZero()
        val picked = issueRequest(setup, "pick", pickBody(setup))
        assertThat(picked.status).withFailMessage(picked.contentAsString).isEqualTo(200)
        assertThat(reservations(setup.stock.token).size()).isZero()
        assertThat(fixture(setup.stock.token).transaction {
            scalar("SELECT sum(reserved_picked_base) FROM inventory_reservation WHERE state='OPEN' AND base_unit='MM'")
        }).isEqualTo("100000")
    }

    private fun reservations(token: String): JsonNode {
        val response = request("GET", "/api/inventory/reservations", token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }
}
