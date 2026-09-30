package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehousePolicyITAuthority : WarehousePolicyHttpFixture() {
    @Test fun `first scope setup is explicit current IAM authorized audited and cannot reopen after revocation`() {
        val setup = setupReceipt()
        val administrator = user(setup.token, setOf("iam.user.assign", "iam.role.create", "inventory.approval.manage", "inventory.location.manage", "inventory.receipt.manage"))
        val principal = mapper.readTree(request("GET", "/api/users/${administrator.second}", setup.token).contentAsString)
        val membership = mapper.writeValueAsString(mapOf("roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(setup.token))))
        assertThat(request("PUT", "/api/users/${administrator.second}/access", setup.token, membership).status).isEqualTo(200)
        val document = draft(setup, costLine(setup)).path("id").asString()
        assertThat(evaluate(administrator.first, document).status).isEqualTo(404)
        val path = "/api/v1/warehouse/settings/scopes/${administrator.second}/${setup.inspection}"
        val grant = request("PUT", path, administrator.first, """{"expectedRevision":0,"active":true}""")
        assertThat(grant.status).withFailMessage(grant.contentAsString).isEqualTo(200)
        assertThat(evaluate(administrator.first, document).status).isEqualTo(200)
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_settings_operation WHERE actor_id='${administrator.second}' AND bootstrap")).isEqualTo("1")
        }
        assertThat(request("PUT", path, setup.token, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(request("PUT", path, administrator.first, """{"expectedRevision":2,"active":true}""").status).isEqualTo(404)
        assertThat(evaluate(administrator.first, document).status).isEqualTo(404)
    }
    @Test fun `concurrent policy replacement has one winner and retains both immutable versions`() {
        val setup = setupReceipt()
        val approver = approver(setup.token, listOf(setup.inspection))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(approver.second)))
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val calls = listOf("200", "300").map { threshold -> pool.submit<Int> {
                check(gate.await(10, TimeUnit.SECONDS))
                request("PUT", "/api/v1/warehouse/settings/policy", setup.token,
                    policyBody(listOf(setup.inspection), listOf(approver.second), threshold = threshold, revision = 1)).status
            } }
            gate.countDown()
            assertThat(calls.map { it.get(20, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(200, 409)
        }
        val history = mapper.readTree(request("GET", "/api/v1/warehouse/settings/policy/history", setup.token).contentAsString)
        assertThat(history.size()).isEqualTo(2)
        assertThat(history.path(1).path("rules").path(0).path("tiers").path(0).path("minimumMinor").asString()).isEqualTo("100")
    }
    @Test fun `concurrent scope grant replays one operation and revocation fences old JWT immediately`() {
        val setup = setupReceipt()
        val manager = approver(setup.token, listOf(setup.inspection), setOf("inventory.location.manage", "inventory.location.view"))
        val target = user(setup.token, setOf("inventory.location.view"))
        val key = UUID.randomUUID().toString()
        val path = "/api/v1/warehouse/settings/scopes/${target.second}/${setup.inspection}"
        val body = """{"expectedRevision":0,"active":true}"""
        val epoch = fixture(setup.token).transaction { scalar("SELECT epoch FROM iam_authorization_epoch").toLong() }
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val calls = (1..2).map { pool.submit<String> { check(gate.await(10, TimeUnit.SECONDS));
                val result = request("PUT", path, manager.first, body, key)
                assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
                result.contentAsString
            } }
            gate.countDown()
            assertThat(calls[0].get(20, TimeUnit.SECONDS)).isEqualTo(calls[1].get(20, TimeUnit.SECONDS))
        }
        fixture(setup.token).transaction { assertThat(scalar("SELECT epoch FROM iam_authorization_epoch").toLong()).isEqualTo(epoch + 1) }
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${manager.second}/${setup.inspection}", setup.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(request("PUT", path, manager.first, body, key).status).isEqualTo(404)
        assertThat(request("POST", "/api/users/${manager.second}/disable", setup.token).status).isEqualTo(200)
        assertThat(request("PUT", path, manager.first, body, key).status).isEqualTo(403)
    }
    @Test fun `delegation is durable scoped bounded and cannot form a chain or cycle`() {
        val setup = setupReceipt()
        val source = approver(setup.token, listOf(setup.inspection))
        val delegate = approver(setup.token, listOf(setup.inspection))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(source.second, delegate.second)))
        val body = delegation(source.second, delegate.second, setup.inspection)
        val key = UUID.randomUUID().toString()
        val created = request("POST", "/api/v1/warehouse/settings/delegations", setup.token, body, key)
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(200)
        assertThat(request("POST", "/api/v1/warehouse/settings/delegations", setup.token, body, key).contentAsString).isEqualTo(created.contentAsString)
        assertThat(request("POST", "/api/v1/warehouse/settings/delegations", setup.token,
            delegation(delegate.second, source.second, setup.inspection)).contentAsString).contains("INDEPENDENT_APPROVER_REQUIRED")
        assertThat(request("POST", "/api/v1/warehouse/settings/delegations", setup.token,
            delegation(source.second, source.second, setup.inspection)).status).isEqualTo(400)
        for (expiry in listOf(Instant.now().minusSeconds(1), Instant.now().plusSeconds(31 * 86400)))
            assertThat(request("POST", "/api/v1/warehouse/settings/delegations", setup.token,
                delegation(source.second, delegate.second, setup.inspection, expiry)).status).isEqualTo(400)
        val id = mapper.readTree(created.contentAsString).path("id").asString()
        val revoked = request("POST", "/api/v1/warehouse/settings/delegations/$id/revoke", setup.token, """{"expectedRevision":1}""")
        assertThat(revoked.status).withFailMessage(revoked.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(revoked.contentAsString).path("revision").asLong()).isEqualTo(2)
        assertThat(request("POST", "/api/v1/warehouse/settings/delegations/$id/revoke", setup.token, """{"expectedRevision":1}""").status).isEqualTo(409)
    }
    @Test fun `delegated requester cannot satisfy their own requirement and revoked grants disappear`() {
        val setup = setupReceipt()
        val independent = approver(setup.token, listOf(setup.inspection))
        val delegate = approver(setup.token, listOf(setup.inspection))
        val me = mapper.readTree(request("GET", "/api/me", setup.token).contentAsString).path("id").asString()
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(me, independent.second)))
        val grant = request("POST", "/api/v1/warehouse/settings/delegations", setup.token, delegation(me, delegate.second, setup.inspection))
        assertThat(grant.status).withFailMessage(grant.contentAsString).isEqualTo(200)
        val document = draft(setup, costLine(setup)).path("id").asString()
        val valid = evaluate(setup.token, document)
        assertThat(valid.status).isEqualTo(200)
        assertThat(mapper.readTree(valid.contentAsString).path("tiers").toString()).doesNotContain(delegate.second, me)
        assertThat(request("POST", "/api/users/${independent.second}/disable", setup.token).status).isEqualTo(200)
        assertThat(evaluate(setup.token, document).contentAsString).contains("INDEPENDENT_APPROVER_REQUIRED")
    }
    @Test fun `server clock expiry removes delegated authority without a restart or cleanup job`() {
        val setup = setupReceipt()
        val source = approver(setup.token, listOf(setup.inspection))
        val delegate = approver(setup.token, listOf(setup.inspection))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(source.second)))
        val document = draft(setup, costLine(setup)).path("id").asString()
        val expiry = Instant.now().plusSeconds(4)
        val grant = request("POST", "/api/v1/warehouse/settings/delegations", setup.token, delegation(source.second, delegate.second, setup.inspection, expiry))
        assertThat(grant.status).withFailMessage(grant.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(evaluate(setup.token, document).contentAsString).path("tiers").toString()).contains(delegate.second)
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).until { Instant.now() > expiry }
        assertThat(mapper.readTree(evaluate(setup.token, document).contentAsString).path("tiers").toString()).doesNotContain(delegate.second)
    }
    private fun delegation(source: String, target: String, location: String, expiry: Instant = Instant.now().plusSeconds(3600)) =
        """{"expectedRevision":0,"approverId":"$source","delegateId":"$target","sourceRoleId":null,"locationId":"$location","operation":"RECEIPT","validUntil":"$expiry"}"""
}
