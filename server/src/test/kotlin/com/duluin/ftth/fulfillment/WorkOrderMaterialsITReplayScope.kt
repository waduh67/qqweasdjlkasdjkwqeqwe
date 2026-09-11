package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class WorkOrderMaterialsITReplayScope : MaterialWorkflowFixture() {
    @ParameterizedTest
    @CsvSource("reserve,WAREHOUSE", "release,WAREHOUSE", "reserve,AREA", "release,AREA",
        "reserve,PERMISSION", "release,PERMISSION", "reserve,ASSIGNMENT", "release,ASSIGNMENT")
    fun `AV13 outer replay must retain owner current scope and authority checks`(transition: String, revoked: String) {
        val setup = setupReceipt()
        receiveStock(setup)
        val actor = technician(setup.token)
        val scopePath = "/api/v1/warehouse/settings/scopes/${actor.second}/${setup.bin}"
        assertThat(request("PUT", scopePath, setup.token, """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val id = workOrder(setup.token)
        assign(setup.token, id, actor.second)
        putPlan(setup.token, id, plan(setup.token, id, "[${line(setup.cable, "120000")}]"))
        val command = command(setup.token, id, 1, "Explicit material action")
        action(setup.token, id, "submit-request", command)
        val reserved = action(actor.first, id, "reserve", command, "av13-reserve")
        val original = if (transition == "reserve") reserved else action(actor.first, id, "release", command, "av13-release")
        val key = "av13-$transition"
        val me = mapper.readTree(request("GET", "/api/me", actor.first).contentAsString)
        val email = me.path("email").asString()
        val fresh = login(email.substringAfter('@').substringBefore(".test"), email)
        assertThat(action(fresh, id, transition, command, key)).isEqualTo(original)
        val fixture = fixture(setup.token)
        val ownerCommand = fixture.transaction {
            mapper.readTree(scalar("""SELECT identity.canonical_payload FROM inventory_command_identity identity
                JOIN inventory_operation operation ON operation.tenant_id=identity.tenant_id AND operation.id=identity.id
                WHERE operation.namespace='warehouse.reservation.$transition' AND operation.operation_key='material:$key'""")).path("request").toString()
        }
        when (revoked) {
            "WAREHOUSE" -> assertThat(request("PUT", scopePath, setup.token, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
            "ASSIGNMENT" -> assign(setup.token, id, technician(setup.token).second)
            else -> {
                val principal = mapper.readTree(request("GET", "/api/users/${actor.second}", setup.token).contentAsString)
                val roles = if (revoked == "PERMISSION") emptyList<String>() else principal.path("roleIds").asSequence().map { it.asString() }.toList()
                val areas = if (revoked == "AREA") emptyList<String>() else listOf(area(setup.token))
                assertThat(request("PUT", "/api/users/${actor.second}/access", setup.token,
                    mapper.writeValueAsString(mapOf("roleIds" to roles, "areaIds" to areas))).status).isEqualTo(200)
            }
        }
        if (revoked == "WAREHOUSE") {
            assertThat(request("GET", "/api/work-orders/$id/materials", actor.first).status).isEqualTo(404)
            assertThat(request("POST", "/api/v1/warehouse/material-requests/${reserved.path("demandDocumentId").asString()}/$transition",
                actor.first, ownerCommand, "material:$key").status).isEqualTo(404)
        }
        val replay = request("POST", "/api/work-orders/$id/materials/$transition", actor.first, command, key)
        assertThat(replay.status).withFailMessage("$revoked $transition leaked ${replay.contentAsString}").isIn(403, 404)
        assertThat(replay.contentAsString).isNotEqualTo(original.toString())
        fixture.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_material_command WHERE action='${transition.uppercase()}'")).isEqualTo("1")
        }
    }
}
