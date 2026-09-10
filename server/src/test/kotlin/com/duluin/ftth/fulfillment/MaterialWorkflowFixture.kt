package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.WarehouseReceiptHttpFixture
import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.JsonNode
import java.util.UUID

abstract class MaterialWorkflowFixture : WarehouseReceiptHttpFixture() {
    protected fun workOrder(token: String, type: String = "PREVENTIVE", customer: String? = null): String {
        val result = request("POST", "/api/work-orders", token, mapper.writeValueAsString(mapOf("type" to type,
            "title" to "Material workflow", "areaId" to area(token), "customerId" to customer)))
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
        return mapper.readTree(result.contentAsString).path("id").asString()
    }
    protected fun summary(token: String, id: String): JsonNode {
        val result = request("GET", "/api/work-orders/$id/materials", token)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        return mapper.readTree(result.contentAsString)
    }
    protected fun line(sku: String, amount: String = "100000", unit: String = "MM") =
        """{"skuId":"$sku","quantityBase":"$amount","baseUnit":"$unit","continuousCut":false}"""
    protected fun plan(token: String, id: String, lines: String?, expected: Long = 0, mode: String = "MATERIAL_REQUIRED", reason: String? = null): String {
        val revision = summary(token, id).path("revisions").path("workOrderRevision").asLong()
        return """{"expectedRevision":$expected,"workOrderRevision":$revision,"materialMode":"$mode","reason":${mapper.writeValueAsString(reason)},"lines":$lines}"""
    }
    protected fun putPlan(token: String, id: String, body: String, key: String = UUID.randomUUID().toString()): JsonNode {
        val result = request("PUT", "/api/work-orders/$id/materials/plan", token, body, key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        return mapper.readTree(result.contentAsString)
    }
    protected fun command(token: String, id: String, revision: Long, reason: String? = null) =
        """{"expectedRevision":$revision,"workOrderRevision":${summary(token, id).path("revisions").path("workOrderRevision").asLong()},"reason":${mapper.writeValueAsString(reason)}}"""
    protected fun action(token: String, id: String, action: String, body: String, key: String = UUID.randomUUID().toString()): JsonNode {
        val result = request("POST", "/api/work-orders/$id/materials/$action", token, body, key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        return mapper.readTree(result.contentAsString)
    }
    protected fun receiveStock(setup: Setup, amount: String = "60000") {
        assertThat(request("PUT", "/api/v1/warehouse/skus/${setup.cable}", setup.token,
            """{"expectedRevision":0,"code":"CABLE","name":"Cable","tracking":"LOT","baseUnit":"MM","inspectionRequired":false}""").status).isEqualTo(200)
        val id = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"$amount","lotCode":"MATERIAL"}""").path("id").asString()
        transition(setup, id, "receive", """{"expectedRevision":0}""")
        val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString).path("lines")[0]
        transition(setup, id, "putaway", """{"expectedRevision":1,"destinationLocationId":"${setup.bin}","lines":[{"lineId":"${line.path("id").asString()}",
            "stockIdentityId":"${line.path("pieces")[0].path("stockIdentityId").asString()}","baseUnit":"MM","quantityBase":"$amount"}]}""")
    }
    protected fun technician(admin: String): Pair<String, String> {
        val user = user(admin, setOf("workorder.order.field", "inventory.request.view", "inventory.request.manage", "inventory.sku.view"))
        val roles = mapper.readTree(request("GET", "/api/roles", admin).contentAsString)
        val techRole = roles.single { it.path("name").asString() == "Teknisi" }.path("id").asString()
        val current = mapper.readTree(request("GET", "/api/me", user.first).contentAsString)
        val roleIds = current.path("roleIds").asSequence().map { it.asString() }.toList() + techRole
        assertThat(request("PUT", "/api/users/${user.second}/access", admin,
            mapper.writeValueAsString(mapOf("roleIds" to roleIds, "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        return user
    }
    protected fun assign(admin: String, id: String, technician: String) {
        val response = request("POST", "/api/work-orders/$id/assign", admin, """{"technicianIds":["$technician"]}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
    }
}
