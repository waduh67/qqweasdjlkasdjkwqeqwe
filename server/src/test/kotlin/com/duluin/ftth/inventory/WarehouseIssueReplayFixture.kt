package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat

abstract class WarehouseIssueReplayFixture : WarehouseIssueFixture() {
    protected val pickerPermissions = setOf("workorder.order.view", "inventory.issue.manage", "inventory.issue.view",
        "inventory.request.manage", "inventory.request.view", "inventory.sku.view")
    protected fun picker(setup: IssueSetup): Pair<String, String> {
        val picker = user(setup.stock.token, pickerPermissions + "inventory.request.override")
        val me = mapper.readTree(request("GET", "/api/me", picker.first).contentAsString)
        assertThat(request("PUT", "/api/users/${picker.second}/access", setup.stock.token, mapper.writeValueAsString(mapOf(
            "roleIds" to me.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(setup.stock.token))))).status).isEqualTo(200)
        scope(setup, picker.second, setup.stock.bin, 0, true)
        return picker
    }
    protected fun overridePermission(setup: IssueSetup, picker: Pair<String, String>, enabled: Boolean) {
        val role = mapper.readTree(request("GET", "/api/me", picker.first).contentAsString).path("roleIds")[0].asString()
        val allowed = pickerPermissions + if (enabled) setOf("inventory.request.override") else emptySet()
        val permissions = mapper.readTree(request("GET", "/api/permissions", setup.stock.token).contentAsString).asSequence()
            .filter { it.path("code").asString() in allowed }.map { it.path("id").asString() }.toList()
        val response = request("PUT", "/api/roles/$role", setup.stock.token, mapper.writeValueAsString(mapOf("name" to "Issue picker", "permissionIds" to permissions)))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
    }
    protected fun transit(setup: IssueSetup) = fixture(setup.stock.token).transaction { scalar("SELECT id FROM inventory_location WHERE code='WO_TRANSIT'") }
    protected fun scope(setup: IssueSetup, user: String, location: String, revision: Int, active: Boolean) {
        val result = request("PUT", "/api/v1/warehouse/settings/scopes/$user/$location", setup.stock.token,
            """{"expectedRevision":$revision,"active":$active}""")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
    }
    protected fun substitutedSetup(): IssueSetup {
        val setup = issuedSetup()
        val stock = setup.stock
        val original = summary(stock.token, setup.workOrder).path("plan").path("lines")[0]
        action(stock.token, setup.workOrder, "release", command(stock.token, setup.workOrder, 1, "Explicit replacement"))
        val replacement = create("skus", stock.token, """{"code":"ALT","name":"Replacement cable","tracking":"LOT","baseUnit":"MM","inspectionRequired":false}""").path("id").asString()
        val receipt = draft(stock, """{"skuId":"$replacement","quantityBase":"100000","lotCode":"ALT-LOT"}""").path("id").asString()
        transition(stock, receipt, "receive", """{"expectedRevision":0}""")
        val received = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$receipt", stock.token).contentAsString).path("lines")[0]
        transition(stock, receipt, "putaway", mapper.writeValueAsString(mapOf("expectedRevision" to 1, "destinationLocationId" to stock.bin,
            "lines" to listOf(mapOf("lineId" to received.path("id").asString(), "stockIdentityId" to received.path("pieces")[0].path("stockIdentityId").asString(),
                "quantityBase" to "100000", "baseUnit" to "MM")))))
        val substitute = line(replacement).dropLast(1) + """, "substitution":{"originalPlanLineId":"${original.path("id").asString()}","originalSkuId":"${stock.cable}","reason":"Authorized compatible replacement"}}"""
        putPlan(stock.token, setup.workOrder, plan(stock.token, setup.workOrder, "[$substitute]", 1))
        action(stock.token, setup.workOrder, "submit-request", command(stock.token, setup.workOrder, 2))
        action(stock.token, setup.workOrder, "reserve", command(stock.token, setup.workOrder, 2))
        return setup
    }
}
