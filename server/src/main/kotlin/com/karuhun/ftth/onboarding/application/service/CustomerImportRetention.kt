package com.karuhun.ftth.onboarding.application.service

import com.karuhun.ftth.common.tenant.TenantContext
import com.karuhun.ftth.tenancy.TenantApi
import com.karuhun.ftth.onboarding.adapter.outbound.persistence.CustomerImportBatchJpaRepository
import com.karuhun.ftth.onboarding.adapter.outbound.persistence.CustomerImportErrorJpaRepository
import com.karuhun.ftth.onboarding.adapter.outbound.persistence.CustomerImportRetentionAuditJpaEntity
import com.karuhun.ftth.onboarding.adapter.outbound.persistence.CustomerImportRetentionAuditJpaRepository
import com.karuhun.ftth.onboarding.adapter.outbound.persistence.CustomerImportStagingRowJpaRepository
import com.karuhun.ftth.onboarding.adapter.outbound.persistence.CustomerImportCredentialVault
import com.karuhun.ftth.bng.CredentialHandle
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Component
class CustomerImportRetentionScheduler(private val tenantApi: TenantApi, private val worker: CustomerImportRetentionWorker) {
    @Scheduled(cron = "\${ftth.onboarding.import-retention-cron:0 40 3 * * *}")
    fun purge() {
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            TenantContext.runAs(tenantId) { worker.purgeExpired() }
        }
    }
}

@Component
class CustomerImportRetentionWorker(
    private val batches: CustomerImportBatchJpaRepository,
    private val staging: CustomerImportStagingRowJpaRepository,
    private val errors: CustomerImportErrorJpaRepository,
    private val audit: CustomerImportRetentionAuditJpaRepository,
    private val credentials: CustomerImportCredentialVault,
) {
    @Transactional
    fun purgeExpired(retention: Duration = Duration.ofDays(30)) {
        val cutoff = Instant.now().minus(retention)
        batches.findAllByRetentionUntilBeforeAndLegalHoldFalse(cutoff).forEach { batch ->
            val credentialHandles = staging.findAllByBatchIdOrderByRowNumber(batch.id).mapNotNull { it.credentialHandleId }
            staging.deleteAllByBatchId(batch.id)
            errors.deleteAllByBatchId(batch.id)
            credentialHandles.forEach { credentials.purge(CredentialHandle(it)) }
            batch.state = CustomerImportBatchState.PURGED
            batches.save(batch)
            audit.save(CustomerImportRetentionAuditJpaEntity().apply { batchId = batch.id; outcome = "PURGED_STAGING_REPORT" })
        }
    }
}
