package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehousePolicyPersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.reset
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant
import java.util.UUID

class WarehouseApprovalITNoDecisionReplay : WarehousePolicyRoleFixture() {
    @MockitoSpyBean lateinit var clock: WarehousePolicyPersistence
    @ParameterizedTest @ValueSource(strings = ["STALE", "EXPIRED"])
    fun `AV12-2 no decision terminal replay still requires current configured role`(status: String) {
        val scenario = roleScenario()
        val admin = scenario.setup.token
        grant(admin, scenario.approver.second, listOf(scenario.setup.source))
        changeRole(admin, scenario.otherRole, setOf("inventory.approval.view", "inventory.approval.decide"))
        if (status == "EXPIRED") doReturn(Instant.now().minusSeconds(172800)).`when`(clock).now()
        val pending = try { request("POST", "/api/v1/warehouse/approvals/request", admin,
            """{"sourceDocumentId":"${scenario.document}","sourceRevision":0}""") } finally { reset(clock) }
        assertThat(pending.status).withFailMessage(pending.contentAsString).isEqualTo(201)
        val id = mapper.readTree(pending.contentAsString).path("requestId").asString()
        if (status == "STALE") fixture(admin).transaction {
            sql("UPDATE inventory_document SET revision=revision+1 WHERE id='${scenario.document}'")
        }
        val key = UUID.randomUUID().toString()
        val input = """{"requestId":"$id","expectedRevision":0,"decision":"APPROVE"}"""
        val original = request("POST", "/api/v1/warehouse/approvals/decide", scenario.approver.first, input, key)
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(original.contentAsString).path("status").asString()).isEqualTo(status)
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", scenario.approver.first, input, key).contentAsString).isEqualTo(original.contentAsString)
        assignRoles(admin, scenario.approver.second, listOf(scenario.otherRole))
        val replay = request("POST", "/api/v1/warehouse/approvals/decide", scenario.approver.first, input, key)
        assertThat(replay.status).withFailMessage(replay.contentAsString).isEqualTo(403)
        fixture(admin).transaction { assertThat(scalar("SELECT count(*) FROM inventory_approval_decision")).isEqualTo("0") }
    }
}
