package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.monitoring.application.port.outbound.IngestBatchRepository
import com.duluin.ftth.tenancy.TenantAuthorityDirectory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class IngestBatchRetention(private val tenants: TenantAuthorityDirectory, private val batches: IngestBatchRepository) {
    companion object {
        fun cutoff(now: Instant): Instant = now.minusSeconds(72 * 3600 + 300)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun tenantIds() = tenants.allTenantIds()

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    fun purge(cutoff: Instant) = batches.deleteOlderThan(cutoff)
}
