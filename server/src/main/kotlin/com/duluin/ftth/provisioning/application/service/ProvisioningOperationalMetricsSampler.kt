package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import jakarta.persistence.EntityManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class ProvisioningOperationalMetricsSampler(
    private val tenants: TenantApi,
    private val entityManager: EntityManager,
    private val metrics: ProvisioningMetrics,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${ftth.provisioning.metrics-interval:PT30S}")
    fun sample() {
        var queued = 0L
        var oldest: Instant? = null
        tenants.findActiveTenantIds().forEach { tenantId ->
            val sample = TenantContext.runAs(tenantId) { sampleTenant() }
            queued += sample.first
            val candidate = sample.second
            if (candidate != null && (oldest == null || candidate.isBefore(oldest))) oldest = candidate
        }
        metrics.queueDepth(queued)
        metrics.oldestPlanAge(oldest?.let { Duration.between(it, clock.instant()) } ?: Duration.ZERO)
    }

    @Transactional(readOnly = true)
    fun sampleTenant(): Pair<Long, Instant?> {
        val queued = (entityManager.createNativeQuery(
            "SELECT count(*) FROM provisioning_execution WHERE status = 'QUEUED'",
        ).singleResult as Number).toLong()
        val oldest = entityManager.createNativeQuery(
            "SELECT min(created_at) FROM provisioning_plan WHERE status = 'VALIDATED'",
        ).singleResult as Instant?
        return queued to oldest
    }
}
