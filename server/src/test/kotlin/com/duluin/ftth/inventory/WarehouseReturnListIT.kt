package com.duluin.ftth.inventory

import com.duluin.ftth.fulfillment.MaterialLifecycleFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID

class WarehouseReturnListIT : MaterialLifecycleFixture() {
    @Test fun `return pages and counts use current location scope before pagination`() {
        val first = residualCase("10000")
        val admin = first.usage.receipt.stock.token
        fun intake(case: ResidualCase, key: String): Pair<JsonNode, JsonNode> {
            val dispatch = dispatchResidual(case, "dispatch-$key")
            assertThat(dispatch.status).withFailMessage(dispatch.contentAsString).isEqualTo(200)
            val source = mapper.readTree(dispatch.contentAsString)
            assertThat(acknowledgeResidual(case, source.path("id").asString(), key = "ack-$key").status).isEqualTo(200)
            val received = request("POST", "/api/v1/warehouse/returns", admin,
                """{"origin":"MATERIAL_RESIDUAL","sourceDocumentId":"${source.path("id").asString()}","quarantineLocationId":"${case.input.targetLocationId}","evidenceReference":"$key"}""", "intake-$key")
            assertThat(received.status).withFailMessage(received.contentAsString).isEqualTo(201)
            return source to mapper.readTree(received.contentAsString)
        }
        val (source, firstView) = intake(first, "first")
        val secondLocation = create("locations", admin,
            """{"code":"SECOND_QUARANTINE","name":"Another warehouse","kind":"QUARANTINE"}""").path("id").asString()
        val second = first.copy(input = first.input.copy(quantityBase = "7500", targetLocationId = UUID.fromString(secondLocation),
            stockIdentityId = UUID.fromString(source.path("remainder").path("stockIdentityId").asString()),
            workOrderRevision = summary(admin, first.usage.receipt.workOrder).path("revisions").path("workOrderRevision").asLong()))
        val secondView = intake(second, "second").second
        fun list(token: String, query: String): JsonNode {
            val response = request("GET", "/api/v1/warehouse/returns?$query", token)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
            return mapper.readTree(response.contentAsString)
        }
        val page0 = list(admin, "page=0&size=1")
        assertThat(page0.path("totalElements").asLong()).isEqualTo(2)
        assertThat(page0.path("items").single().path("id").asString()).isEqualTo(secondView.path("id").asString())
        assertThat(list(admin, "page=1&size=1").path("items").single().path("id").asString()).isEqualTo(firstView.path("id").asString())
        assertThat(list(admin, "page=2&size=1").path("items").size()).isZero()
        assertThat(list(admin, "state=RECEIVED_IN_INSPECTION&origin=MATERIAL_RESIDUAL").path("totalElements").asLong()).isEqualTo(2)
        assertThat(list(admin, "origin=ASSET_REMOVAL").path("totalElements").asLong()).isZero()
        assertThat(list(admin, "locationId=${first.input.targetLocationId}").path("items").single().path("id").asString())
            .isEqualTo(firstView.path("id").asString())
        val viewer = user(admin, setOf("inventory.return.view"))
        val principal = mapper.readTree(request("GET", "/api/users/${viewer.second}", admin).contentAsString)
        assertThat(request("PUT", "/api/users/${viewer.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(),
            "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        val scope = "/api/v1/warehouse/settings/scopes/${viewer.second}/${first.input.targetLocationId}"
        assertThat(request("PUT", scope, admin, """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val visible = list(viewer.first, "page=0&size=1")
        assertThat(visible.path("totalElements").asLong()).isEqualTo(1)
        assertThat(visible.path("items").single().path("id").asString()).isEqualTo(firstView.path("id").asString())
        assertThat(list(viewer.first, "page=1&size=1").path("items").size()).isZero()
        assertThat(list(viewer.first, "locationId=$secondLocation").path("totalElements").asLong()).isZero()
        assertThat(request("PUT", scope, admin, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(list(viewer.first, "page=0&size=1").path("totalElements").asLong()).isZero()
        assertThat(list(tenant(), "page=0&size=1").path("totalElements").asLong()).isZero()
        for (invalid in listOf("page=-1", "size=0", "size=101", "state=BOGUS"))
            assertThat(request("GET", "/api/v1/warehouse/returns?$invalid", admin).status).isEqualTo(400)
    }
}
