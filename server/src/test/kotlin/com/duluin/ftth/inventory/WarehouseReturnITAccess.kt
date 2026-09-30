package com.duluin.ftth.inventory

import com.duluin.ftth.fulfillment.MaterialLifecycleFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseReturnITAccess : MaterialLifecycleFixture() {
    @Test fun `original JWT and command keys cannot reveal a return after warehouse scope revocation`() {
        val case = residualCase()
        val admin = case.usage.receipt.stock.token
        val residual = dispatchedResidual(case)
        assertThat(acknowledgeResidual(case, residual).status).isEqualTo(200)
        val inspector = user(admin, setOf("inventory.return.view", "inventory.return.manage"))
        val principal = mapper.readTree(request("GET", "/api/users/${inspector.second}", admin).contentAsString)
        assertThat(request("PUT", "/api/users/${inspector.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(),
            "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        val scope = "/api/v1/warehouse/settings/scopes/${inspector.second}/${case.input.targetLocationId}"
        assertThat(request("PUT", scope, admin, """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val intake = """{"origin":"MATERIAL_RESIDUAL","sourceDocumentId":"$residual","quarantineLocationId":"${case.input.targetLocationId}","evidenceReference":"scoped-return-receipt"}"""
        val created = request("POST", "/api/v1/warehouse/returns", inspector.first, intake, "scoped-intake")
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
        val id = mapper.readTree(created.contentAsString).path("id").asString()
        val path = "/api/v1/warehouse/returns/$id"
        val inspection = """{"expectedRevision":0,"measuredQuantityBase":"17500","condition":"DAMAGED","destinationLocationId":"${case.input.targetLocationId}","evidenceReference":"scoped-inspection","resetConfirmed":false}"""
        val inspected = request("POST", "$path/inspect", inspector.first, inspection, "scoped-inspect")
        assertThat(inspected.status).withFailMessage(inspected.contentAsString).isEqualTo(200)
        val before = fixture(admin).transaction { scalar("SELECT count(*) FROM inventory_movement") }
        assertThat(request("PUT", scope, admin, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(request("GET", path, inspector.first).status).isEqualTo(404)
        assertThat(request("GET", "$path/history", inspector.first).status).isEqualTo(404)
        assertThat(request("POST", "/api/v1/warehouse/returns", inspector.first, intake, "scoped-intake").status).isEqualTo(404)
        assertThat(request("POST", "$path/inspect", inspector.first, inspection, "scoped-inspect").status).isEqualTo(404)
        assertThat(request("PUT", scope, admin, """{"expectedRevision":2,"active":true}""").status).isEqualTo(200)
        assertThat(request("POST", "/api/v1/warehouse/returns", inspector.first, intake, "scoped-intake").contentAsString).isEqualTo(created.contentAsString)
        assertThat(request("POST", "$path/inspect", inspector.first, inspection, "scoped-inspect").contentAsString).isEqualTo(inspected.contentAsString)
        fixture(admin).transaction { assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(before) }
    }
}
