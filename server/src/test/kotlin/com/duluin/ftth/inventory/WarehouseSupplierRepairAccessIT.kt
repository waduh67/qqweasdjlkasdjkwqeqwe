package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseSupplierRepairAccessIT : WarehouseRepairFixture() {
    @Test fun `repair command replay checks current warehouse scope before returning supplier receipts`() {
        val setup = repairSetup()
        val actor = user(setup.token, setOf("inventory.return.view", "inventory.return.manage"))
        val principal = mapper.readTree(request("GET", "/api/users/${actor.second}", setup.token).contentAsString)
        assertThat(request("PUT", "/api/users/${actor.second}/access", setup.token, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(),
            "areaIds" to listOf(area(setup.token))))).status).isEqualTo(200)
        fun scope(location: String, revision: Long, active: Boolean) {
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${actor.second}/$location", setup.token,
                """{"expectedRevision":$revision,"active":$active}""").status).isEqualTo(200)
        }
        scope(setup.returned.quarantine, 0, true)
        scope(setup.location, 0, true)
        val outbound = dispatchRepair(setup, actor.first)
        scope(setup.location, 1, false)
        assertThat(request("GET", setup.path, actor.first).status).isEqualTo(404)
        assertThat(request("POST", "${setup.path}/repair-dispatch", actor.first, setup.dispatchBody, "supplier-outbound").status).isEqualTo(404)
        scope(setup.location, 2, true)
        assertThat(dispatchRepair(setup, actor.first)).isEqualTo(outbound)
        val inbound = receiveRepair(setup, outbound, actor.first)
        val movements = fixture(setup.token).transaction { scalar("SELECT count(*) FROM inventory_movement") }
        scope(setup.returned.quarantine, 1, false)
        assertThat(request("GET", "${setup.path}/history", actor.first).status).isEqualTo(404)
        assertThat(request("POST", "${setup.path}/repair-receive", actor.first,
            setup.receiptBody(outbound.path("revision").asLong()), "supplier-inbound").status).isEqualTo(404)
        scope(setup.returned.quarantine, 2, true)
        assertThat(receiveRepair(setup, outbound, actor.first)).isEqualTo(inbound)
        fixture(setup.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(movements) }
    }
}
