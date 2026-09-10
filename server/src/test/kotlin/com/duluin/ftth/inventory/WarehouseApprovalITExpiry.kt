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
