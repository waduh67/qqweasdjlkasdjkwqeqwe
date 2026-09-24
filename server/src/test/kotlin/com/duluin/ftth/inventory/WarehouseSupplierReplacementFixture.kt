package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.JsonNode

abstract class WarehouseSupplierReplacementFixture : WarehouseRepairFixture() {
    protected data class ReplacementCase(val repair: RepairSetup, val outbound: JsonNode, val receipt: String, val body: String) {
        val token get() = repair.token
    }

    protected fun replacement(cost: String? = "100"): ReplacementCase {
        val repair = repairSetup()
        return replacement(repair, dispatchRepair(repair), "VENDOR-NEW-1", "vendor-new-1", cost)
    }

    protected fun replacement(repair: RepairSetup, outbound: JsonNode, serial: String, key: String, cost: String? = "100"): ReplacementCase {
        val stock = repair.returned.old.installation.receipt.stock
        val body = """{"expectedRevision":${outbound.path("revision").asLong()},"externalReference":"$key","sourceLocationId":"${stock.source}","inspectionLocationId":"${repair.returned.quarantine}","skuId":"${stock.onu}","serial":"$serial","evidenceReference":"vendor-delivery-$key"${cost?.let { ",\"cost\":{\"totalMinor\":\"$it\",\"currency\":\"IDR\"}" } ?: ""}}"""
        val result = request("POST", "${repair.path}/replacement-receipts", repair.token, body, key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
        return ReplacementCase(repair, outbound, mapper.readTree(result.contentAsString).path("receiptId").asString(), body)
    }

    protected fun receiver(case: ReplacementCase, permissions: Set<String>): Pair<String, String> {
        val actor = user(case.token, permissions)
        val principal = mapper.readTree(request("GET", "/api/users/${actor.second}", case.token).contentAsString)
        assertThat(request("PUT", "/api/users/${actor.second}/access", case.token, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(case.token))))).status).isEqualTo(200)
        for (location in listOf(case.repair.returned.quarantine, case.repair.returned.old.installation.receipt.stock.source)) {
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${actor.second}/$location", case.token,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        }
        return actor
    }

    protected fun replacementPolicy(case: ReplacementCase): Pair<String, String> {
        val checker = receiver(case, setOf("inventory.approval.view", "inventory.approval.decide"))
        val policy = request("PUT", "/api/v1/warehouse/settings/policy", case.token,
            """{"expectedRevision":0,"currency":"IDR","expiryHours":24,"warehouseIds":["${case.repair.returned.quarantine}"],"rules":[{"operation":"RECEIPT","tiers":[{"minimumMinor":"1","userIds":["${checker.second}"],"roleIds":[]}]}]}""")
        assertThat(policy.status).withFailMessage(policy.contentAsString).isEqualTo(200)
        return checker
    }

    protected fun replacementApproval(case: ReplacementCase, key: String = "replacement-approval"): String {
        val result = request("POST", "/api/v1/warehouse/approvals/request", case.token,
            """{"sourceDocumentId":"${case.receipt}","sourceRevision":0}""", key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
        return mapper.readTree(result.contentAsString).path("requestId").asString()
    }

    protected fun replacementDecision(approval: String, actor: String, key: String = "replacement-decision", action: String = "APPROVE") =
        request("POST", "/api/v1/warehouse/approvals/decide", actor,
            """{"requestId":"$approval","expectedRevision":0,"decision":"$action","reason":"Vendor replacement source reviewed"}""", key)

    protected fun receiveReplacement(case: ReplacementCase, actor: String = case.token, key: String = "receive-replacement") =
        request("POST", "/api/v1/warehouse/receipts/${case.receipt}/receive", actor, """{"expectedRevision":0}""", key)

    protected fun replacementAsset(case: ReplacementCase): String = fixture(case.token).transaction {
        scalar("SELECT replacement_asset_id::text FROM inventory_repair_replacement_receipt WHERE receipt_id='${case.receipt}'")
    }
}
