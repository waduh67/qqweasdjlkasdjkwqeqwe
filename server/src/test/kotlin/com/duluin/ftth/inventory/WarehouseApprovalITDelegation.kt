package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class WarehouseApprovalITDelegation : WarehouseApprovalHttpFixture() {
    @Test fun `actual decision snapshots active delegation and revocation denies terminal replay`() {
        val case = pending()
        val delegate = approver(case.setup.token, listOf(case.setup.source, case.setup.inspection))
        val grant = request("POST", "/api/v1/warehouse/settings/delegations", case.setup.token,
            delegation(case.checker.second, delegate.second, case.setup.inspection))
        assertThat(grant.status).withFailMessage(grant.contentAsString).isEqualTo(200)
        val key = java.util.UUID.randomUUID().toString()
        val result = decide(case, delegate.first, key = key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        fixture(case.setup.token).transaction {
            assertThat(scalar("SELECT delegated_from::text FROM inventory_approval_decision")).isEqualTo(case.checker.second)
            assertThat(scalar("SELECT independence_snapshot->'delegation'->>'locationId' FROM inventory_approval_decision")).isEqualTo(case.setup.inspection)
        }
        val id = mapper.readTree(grant.contentAsString).path("id").asString()
        assertThat(request("POST", "/api/v1/warehouse/settings/delegations/$id/revoke", case.setup.token, """{"expectedRevision":1}""").status).isEqualTo(200)
        assertThat(decide(case, delegate.first, key = key).status).isEqualTo(403)
        counts(case, 1, 1)
    }
    @Test fun `delegated requester is excluded even when delegate has a direct configured role`() {
        val setup = setupReceipt()
        val checker = approver(setup.token, listOf(setup.source, setup.inspection))
        val delegate = approver(setup.token, listOf(setup.source, setup.inspection))
        val maker = mapper.readTree(request("GET", "/api/me", setup.token).contentAsString).path("id").asString()
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(checker.second, maker, delegate.second)))
        val grant = request("POST", "/api/v1/warehouse/settings/delegations", setup.token, delegation(maker, delegate.second, setup.inspection))
        assertThat(grant.status).withFailMessage(grant.contentAsString).isEqualTo(200)
        val case = submit(setup, checker, draft(setup, costLine(setup)).path("id").asString())
        assertThat(decide(case, delegate.first).status).isEqualTo(403)
        counts(case, 0, 0)
    }
    private fun delegation(source: String, target: String, location: String) =
        """{"expectedRevision":0,"approverId":"$source","delegateId":"$target","sourceRoleId":null,
            "locationId":"$location","operation":"RECEIPT","validUntil":"${Instant.now().plusSeconds(3600)}"}"""
}
