package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehousePolicyPersistence
import com.duluin.ftth.inventory.application.service.WarehouseApprovalExpiry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.reset
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseApprovalITExpiry : WarehouseApprovalHttpFixture() {
    @MockitoSpyBean lateinit var clock: WarehousePolicyPersistence

    @Test fun `expiry and final decision race has one terminal revision and zero physical effects`() {
        doReturn(Instant.now().minusSeconds(48 * 3600)).`when`(clock).now()
        val case = try { pending() } finally { reset(clock) }
        val fixture = fixture(case.setup.token)
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val expiration = pool.submit<Boolean> {
                check(gate.await(10, TimeUnit.SECONDS))
                com.duluin.ftth.common.tenant.TenantContext.runAs(fixture.tenant) { context.getBean(WarehouseApprovalExpiry::class.java).expireOne() }
            }
            val decision = pool.submit<Int> { check(gate.await(10, TimeUnit.SECONDS)); decide(case).status }
            gate.countDown()
            expiration.get(30, TimeUnit.SECONDS)
            assertThat(decision.get(30, TimeUnit.SECONDS)).isEqualTo(409)
        }
        fixture.transaction { assertThat(scalar("SELECT status||':'||revision::text FROM inventory_approval")).isEqualTo("EXPIRED:1") }
        counts(case, 0, 0)
    }

    @Test fun `delegation expiry is checked at actual decision time rather than request candidate time`() {
        val case = pending()
        val delegate = approver(case.setup.token, listOf(case.setup.source, case.setup.inspection))
        val grant = request("POST", "/api/v1/warehouse/settings/delegations", case.setup.token,
            """{"expectedRevision":0,"approverId":"${case.checker.second}","delegateId":"${delegate.second}","sourceRoleId":null,
                "locationId":"${case.setup.inspection}","operation":"RECEIPT","validUntil":"${Instant.now().plusSeconds(3600)}"}""")
        assertThat(grant.status).withFailMessage(grant.contentAsString).isEqualTo(200)
        doReturn(Instant.now().plusSeconds(7200)).`when`(clock).now()
        try { assertThat(decide(case, delegate.first).status).isEqualTo(403) } finally { reset(clock) }
        counts(case, 0, 0)
    }

    @Test fun `idle expired queue transitions once across two workers without physical effects`() {
        doReturn(Instant.now().minusSeconds(48 * 3600)).`when`(clock).now()
        val case = try { pending() } finally { reset(clock) }
        val fixture = fixture(case.setup.token)
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val calls = (1..2).map { pool.submit<Boolean> {
                check(gate.await(10, TimeUnit.SECONDS))
                com.duluin.ftth.common.tenant.TenantContext.runAs(fixture.tenant) { context.getBean(WarehouseApprovalExpiry::class.java).expireOne() }
            } }
            gate.countDown()
            assertThat(calls.map { it.get(30, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(true, false)
        }
        val result = request("GET", "/api/v1/warehouse/approvals/${case.id}", case.checker.first)
        assertThat(result.status).isEqualTo(200)
        assertThat(mapper.readTree(result.contentAsString).path("status").asString()).isEqualTo("EXPIRED")
        fixture.transaction { assertThat(scalar("SELECT revision FROM inventory_approval")).isEqualTo("1") }
        counts(case, 0, 0)
    }
    @Test fun `expired decision stores exact no effect response and never edits expiry`() {
        doReturn(Instant.now().minusSeconds(48 * 3600)).`when`(clock).now()
        val case = try { pending() } finally { reset(clock) }
        val before = fixture(case.setup.token).transaction { scalar("SELECT expires_at::text FROM inventory_approval") }
        val key = java.util.UUID.randomUUID().toString()
        val result = decide(case, key = key)
        assertThat(result.status).isEqualTo(409)
        assertThat(mapper.readTree(result.contentAsString).path("status").asString()).isEqualTo("EXPIRED")
        assertThat(decide(case, key = key).contentAsString).isEqualTo(result.contentAsString)
        fixture(case.setup.token).transaction { assertThat(scalar("SELECT expires_at::text FROM inventory_approval")).isEqualTo(before) }
        counts(case, 0, 0)
    }
}
