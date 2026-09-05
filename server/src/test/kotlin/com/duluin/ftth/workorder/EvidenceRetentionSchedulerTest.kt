package com.duluin.ftth.workorder

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.workorder.application.service.EvidenceRetentionScheduler
import com.duluin.ftth.workorder.application.service.EvidenceRetentionWorker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import java.time.Clock
import java.time.ZoneOffset
import java.time.Instant
import java.util.UUID

class EvidenceRetentionSchedulerTest {
    @Test
    fun `scheduler runs once per tenant in each tenant context and restores null after success`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val seen = runScheduler(prior = null, tenants = listOf(first, second))

        assertThat(seen).containsExactly(first, second)
        assertThat(TenantContext.tenantIdOrNull()).isNull()
    }

    @Test
    fun `scheduler restores null after worker failure`() {
        val tenant = UUID.randomUUID()

        assertThatThrownBy { runScheduler(prior = null, tenants = listOf(tenant), fail = true) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(TenantContext.tenantIdOrNull()).isNull()
    }

    @Test
    fun `scheduler restores non-null prior context after success`() {
        val prior = UUID.randomUUID()
        val seen = TenantContext.runAs(prior) {
            val observed = runScheduler(prior = null, tenants = listOf(UUID.randomUUID()))
            assertThat(TenantContext.tenantIdOrNull()).isEqualTo(prior)
            observed
        }

        assertThat(seen).hasSize(1)
        assertThat(TenantContext.tenantIdOrNull()).isNull()
    }

    @Test
    fun `scheduler restores non-null prior context after worker failure`() {
        val prior = UUID.randomUUID()
        TenantContext.runAs(prior) {
            assertThatThrownBy {
                runScheduler(prior = null, tenants = listOf(UUID.randomUUID()), fail = true)
            }.isInstanceOf(IllegalStateException::class.java)
            assertThat(TenantContext.tenantIdOrNull()).isEqualTo(prior)
        }
        assertThat(TenantContext.tenantIdOrNull()).isNull()
    }

    @Test
    fun `scheduler subtracts calendar months in UTC`() {
        val tenant = UUID.randomUUID()
        var cutoff: Instant? = null
        val tenantApi = mock(TenantApi::class.java)
        val worker = mock(EvidenceRetentionWorker::class.java, Answer {
            if (it.method.name == "purge") cutoff = it.arguments[0] as Instant
            null
        })
        `when`(tenantApi.findActiveTenantIds()).thenReturn(listOf(tenant))

        EvidenceRetentionScheduler(
            tenantApi,
            worker,
            Clock.fixed(Instant.parse("2026-03-31T12:00:00Z"), ZoneOffset.UTC),
            ZoneOffset.UTC,
        ).purge()

        assertThat(cutoff).isEqualTo(Instant.parse("2024-03-31T12:00:00Z"))
    }

    private fun runScheduler(prior: UUID?, tenants: List<UUID>, fail: Boolean = false): List<UUID> {
        val tenantApi = mock(TenantApi::class.java)
        val seen = mutableListOf<UUID>()
        val worker = mock(
            EvidenceRetentionWorker::class.java,
            Answer {
                if (it.method.name == "purge") {
                    if (fail) throw IllegalStateException("boom")
                    seen += TenantContext.tenantId()
                }
                null
            },
        )
        `when`(tenantApi.findActiveTenantIds()).thenReturn(tenants)

        return TenantContext.runAs(prior) {
            EvidenceRetentionScheduler(tenantApi, worker).purge()
            seen
        }
    }
}
