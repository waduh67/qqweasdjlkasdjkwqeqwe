package com.duluin.ftth.hotspot.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.hotspot.application.port.outbound.VoucherRepository
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Component
class VoucherExpiryScheduler(
    private val tenantApi: TenantApi,
    private val runner: VoucherExpiryRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.hotspot.voucher-expiry-scan-interval:PT1M}")
    fun expireDueVouchers() {
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching { TenantContext.runAs(tenantId) { runner.expire() } }
                .onFailure { log.warn("Voucher expiry sweep tenant {} failed: {}", tenantId, it.message) }
        }
    }
}

@Component
class VoucherExpiryRunner(
    private val vouchers: VoucherRepository,
    private val bngApi: BngApi,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun expire(): Int = expire(clock.instant())

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun expire(now: Instant): Int = vouchers.findActiveExpired(now, BATCH_SIZE)
        .count { voucher ->
            vouchers.expireIfDue(voucher.id, now)?.also { revokeAndDisconnect(it.id) } != null
        }

    private fun revokeAndDisconnect(voucherId: UUID) {
        val externalId = voucherId.toString()
        runCatching { bngApi.revokeVoucherCredential(externalId) }
            .onFailure { log.warn("Deprovision voucher {} tertunda: {}", voucherId, it.message) }
        runCatching { bngApi.disconnectVoucherCredential(externalId) }
            .onFailure { log.warn("Disconnect voucher {} tertunda: {}", voucherId, it.message) }
    }

    private companion object {
        const val BATCH_SIZE = 500
    }
}
