package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.springframework.mock.web.MockHttpServletResponse

abstract class WarehouseReturnTitleFixture : WarehouseRepairFixture() {
    protected data class TitleSetup(val repair: RepairSetup, val checker: Pair<String, String>) {
        val returned get() = repair.returned
        val token get() = repair.token
        val body get() = """{"expectedRevision":${returned.revision},"reason":"Customer transfers returned device to ISP","titleTransferReference":"signed-return-transfer","evidenceId":"${returned.old.signature}"}"""
    }
    protected data class TitlePending(val document: String, val approval: String) {
        fun decision(value: String = "APPROVE") = """{"requestId":"$approval","expectedRevision":0,"decision":"$value","reason":"Independent customer title evidence review"}"""
    }

    protected fun titleSetup(): TitleSetup {
        val repair = repairSetup()
        val token = repair.token
        val checker = user(token, setOf("inventory.approval.view", "inventory.approval.decide"))
        val principal = mapper.readTree(request("GET", "/api/users/${checker.second}", token).contentAsString)
        assertThat(request("PUT", "/api/users/${checker.second}/access", token, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(token))))).status).isEqualTo(200)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${checker.second}/${repair.returned.quarantine}", token,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val policy = request("PUT", "/api/v1/warehouse/settings/policy", token,
            """{"expectedRevision":0,"currency":"IDR","expiryHours":24,"warehouseIds":["${repair.returned.quarantine}"],"rules":[{"operation":"TITLE_REACQUISITION","tiers":[{"minimumMinor":"1","userIds":["${checker.second}"],"roleIds":[]}]}]}""")
        assertThat(policy.status).withFailMessage(policy.contentAsString).isEqualTo(200)
        return TitleSetup(repair, checker.first to checker.second.toString())
    }

    protected fun pendingTitle(setup: TitleSetup, key: String = "quarantine-title"): TitlePending {
        val result = request("POST", "${setup.repair.path}/reacquisition", setup.token, setup.body, "$key-request")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
        val document = mapper.readTree(result.contentAsString).path("documentId").asString()
        val approval = request("POST", "/api/v1/warehouse/approvals/request", setup.token,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", "$key-approval")
        assertThat(approval.status).withFailMessage(approval.contentAsString).isEqualTo(201)
        return TitlePending(document, mapper.readTree(approval.contentAsString).path("requestId").asString())
    }

    protected fun decideTitle(setup: TitleSetup, pending: TitlePending, key: String = "quarantine-title-decision",
        value: String = "APPROVE"): MockHttpServletResponse = request("POST", "/api/v1/warehouse/approvals/decide",
        setup.checker.first, pending.decision(value), key)

    protected fun titleStatus(result: MockHttpServletResponse, expected: String) {
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(result.contentAsString).path("status").asString()).isEqualTo(expected)
    }
}
