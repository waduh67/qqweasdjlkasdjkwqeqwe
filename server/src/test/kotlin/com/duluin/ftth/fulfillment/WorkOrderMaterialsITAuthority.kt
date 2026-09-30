package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WorkOrderMaterialsITAuthority : MaterialWorkflowFixture() {
    @ParameterizedTest @ValueSource(strings = ["REASSIGN", "ROLE", "AREA", "DISABLED"])
    fun `original technician JWT cannot replay after current assignment permission area or account revocation`(mode: String) {
        val admin = tenant()
        val technician = technician(admin)
        val id = workOrder(admin)
        assign(admin, id, technician.second)
        val body = plan(admin, id, "[]", mode = "NONE", reason = "Inspect only")
        val created = request("PUT", "/api/work-orders/$id/materials/plan", technician.first, body, "field-plan")
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(200)
        when (mode) {
            "REASSIGN" -> assign(admin, id, technician(admin).second)
            "DISABLED" -> assertThat(request("POST", "/api/users/${technician.second}/disable", admin).status).isEqualTo(200)
            else -> {
                val user = mapper.readTree(request("GET", "/api/users/${technician.second}", admin).contentAsString)
                val roleIds: List<String> = if (mode == "ROLE") emptyList() else user.path("roleIds").asSequence().map { it.asString() }.toList()
                val areaIds: List<String> = if (mode == "AREA") emptyList() else listOf(area(admin))
                assertThat(request("PUT", "/api/users/${technician.second}/access", admin,
                    mapper.writeValueAsString(mapOf("roleIds" to roleIds, "areaIds" to areaIds))).status).isEqualTo(200)
            }
        }
        val replay = request("PUT", "/api/work-orders/$id/materials/plan", technician.first, body, "field-plan")
        assertThat(replay.status).withFailMessage(replay.contentAsString).isIn(401, 403, 404, 409)
        fixture(admin).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_material_plan")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_material_command WHERE action='PLAN'")).isEqualTo("1")
        }
    }
}
