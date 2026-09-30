package com.duluin.ftth.inventory

import com.duluin.ftth.fulfillment.MaterialLifecycleFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID

class WarehouseReturnWorkbenchIT : MaterialLifecycleFixture() {
    @Test fun `eligible named sources are scoped before paging and intake never receives acknowledged remnants twice`() {
        val first = residualCase("10000")
        val admin = first.usage.receipt.stock.token
        fun read(path: String, token: String = admin): JsonNode {
            val response = request("GET", "/api/v1/warehouse/returns$path", token)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
            return mapper.readTree(response.contentAsString)
        }
        val dispatched = dispatchResidual(first, "discovery-first")
        assertThat(dispatched.status).isEqualTo(200)
        val source = mapper.readTree(dispatched.contentAsString)
        val sourceId = source.path("id").asString()
        assertThat(read("/sources").path("totalElements").asLong()).isZero()
        assertThat(acknowledgeResidual(first, sourceId, key = "discovery-first-ack").status).isEqualTo(200)
        val option = read("/sources").path("items").single()
        assertThat(option.path("sourceDocumentId").asString()).isEqualTo(sourceId)
        assertThat(option.path("quantityBase").asString()).isEqualTo("10000")
        assertThat(option.path("quarantineLocationId").asString()).isEqualTo(first.input.targetLocationId.toString())
        assertThat(option.path("item").path("name").asString()).isNotBlank()
        assertThat(option.path("location").path("name").asString()).isNotBlank()
        assertThat(option.path("item").has("cost")).isFalse()
        val secondLocation = create("locations", admin,
            """{"code":"SOURCE_Q_TWO","name":"Second remnant inspection","kind":"QUARANTINE"}""").path("id").asString()
        val second = first.copy(input = first.input.copy(quantityBase = "7500", targetLocationId = UUID.fromString(secondLocation),
            stockIdentityId = UUID.fromString(source.path("remainder").path("stockIdentityId").asString()),
            workOrderRevision = summary(admin, first.usage.receipt.workOrder).path("revisions").path("workOrderRevision").asLong()))
        val secondSource = dispatchedResidual(second)
        assertThat(acknowledgeResidual(second, secondSource).status).isEqualTo(200)
        assertThat(read("/sources?size=1").path("totalElements").asLong()).isEqualTo(2)
        assertThat(read("/sources?size=1").path("items").single().path("sourceDocumentId").asString()).isEqualTo(secondSource)
        assertThat(read("/sources?size=1&page=1").path("items").single()).isEqualTo(option)
        assertThat(read("/sources?size=1&page=2").path("items").size()).isZero()
        assertThat(read("/sources?query=${option.path("code").asString()}").path("totalElements").asLong()).isEqualTo(1)
        assertThat(read("/sources?owner=CUSTOMER").path("totalElements").asLong()).isZero()
        assertThat(read("/sources?from=2000-01-01T00:00:00Z&until=2000-01-02T00:00:00Z").path("totalElements").asLong()).isZero()

        val viewer = user(admin, setOf("inventory.return.view", "inventory.return.manage"))
        val principal = mapper.readTree(request("GET", "/api/users/${viewer.second}", admin).contentAsString)
        assertThat(request("PUT", "/api/users/${viewer.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        val scope = "/api/v1/warehouse/settings/scopes/${viewer.second}/${first.input.targetLocationId}"
        assertThat(request("PUT", scope, admin, """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        assertThat(read("/sources?size=1", viewer.first).path("totalElements").asLong()).isEqualTo(1)
        assertThat(read("/sources?size=1", viewer.first).path("items").single()).isEqualTo(option)
        assertThat(read("/sources?size=1&page=1", viewer.first).path("items").size()).isZero()
        assertThat(read("/sources", tenant()).path("totalElements").asLong()).isZero()
        assertThat(request("GET", "/api/v1/warehouse/returns/sources", user(admin, setOf("inventory.return.view")).first).status).isEqualTo(403)

        val before = fixture(admin).transaction { scalar("SELECT count(*) FROM inventory_movement") }
        val intake = request("POST", "/api/v1/warehouse/returns", viewer.first,
            """{"origin":"MATERIAL_RESIDUAL","sourceDocumentId":"$sourceId","quarantineLocationId":"${first.input.targetLocationId}","evidenceReference":"measured-whole-remnant"}""")
        assertThat(intake.status).withFailMessage(intake.contentAsString).isEqualTo(201)
        fixture(admin).transaction { assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(before) }
        val view = mapper.readTree(intake.contentAsString)
        val id = view.path("id").asString()
        assertThat(read("/sources", viewer.first).path("totalElements").asLong()).isZero()
        assertThat(read("/sources").path("totalElements").asLong()).isEqualTo(1)
        val detail = read("/$id/details", viewer.first)
        assertThat(detail.path("returnCase")).isEqualTo(view)
        assertThat(detail.path("references").path("sourceCode")).isEqualTo(option.path("code"))
        assertThat(detail.path("references").path("receivedByName").asString()).isNotBlank()
        assertThat(detail.path("references").path("assetOrigin").isNull).isTrue()
        assertThat(detail.path("references").has("email")).isFalse()
        assertThat(read("/workbench?size=1", viewer.first).path("items").single()).isEqualTo(detail)
        assertThat(read("/workbench?skuId=${option.path("item").path("id").asString()}", viewer.first).path("totalElements").asLong()).isEqualTo(1)
        assertThat(request("PUT", "/api/v1/warehouse/skus/${option.path("item").path("id").asString()}", admin,
            """{"code":"CABLE","name":"Current remnant cable","tracking":"LOT","baseUnit":"MM","inspectionRequired":false,"expectedRevision":1}""").status).isEqualTo(200)
        assertThat(read("/$id/details", viewer.first).path("references").path("item").path("name").asString()).isEqualTo("Current remnant cable")
        assertThat(read("/$id", viewer.first)).isEqualTo(view)
        assertThat(read("/$id/history", viewer.first).single()).isEqualTo(view)
        assertThat(read("/$id/history/page", viewer.first).path("items").single()).isEqualTo(view)
        assertThat(request("PUT", scope, admin, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(read("/workbench?size=1", viewer.first).path("totalElements").asLong()).isZero()
        for (suffix in listOf("", "/details", "/history", "/history/page"))
            assertThat(request("GET", "/api/v1/warehouse/returns/$id$suffix", viewer.first).status).isEqualTo(404)
        assertThat(request("GET", "/api/v1/warehouse/returns/$id/details", tenant()).status).isEqualTo(404)
        for (path in listOf("", "/workbench", "/sources")) for (invalid in listOf("size=101", "size=0", "page=-1", "page=0&page=1", "query=", "query="+"a".repeat(201),
            "origin=WRONG", "locationId=1-1-1-1-1", "extra=true", "from=2026-01-01T00:00:00Z", "from=2026-02-01T00:00:00Z&until=2026-01-01T00:00:00Z"))
            assertThat(request("GET", "/api/v1/warehouse/returns$path?$invalid", admin).status).describedAs("$path?$invalid").isEqualTo(400)
        for (path in listOf("/history", "/history/page", "/replacement-receipts"))
            assertThat(request("GET", "/api/v1/warehouse/returns/$id$path?page=0&page=1", admin).status).isEqualTo(400)
        assertThat(request("GET", "/api/v1/warehouse/returns/sources?state=RECEIVED_IN_INSPECTION", admin).status).isEqualTo(400)
    }
}
