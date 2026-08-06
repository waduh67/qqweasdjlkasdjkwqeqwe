package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.application.port.outbound.TenantPayoutRepository
import com.duluin.ftth.billing.domain.model.TenantPayout
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Component

/**
 * Adapter riwayat penyaluran dana per-tenant. RLS menyaring ke tenant aktif; [findByReference]
 * (via ref Pivot) dipakai rekonsiliasi callback yang berjalan dalam `TenantContext.runAs`.
 */
@Component
class TenantPayoutPersistenceAdapter(
    private val jpa: TenantPayoutJpaRepository,
) : TenantPayoutRepository {

    override fun save(payout: TenantPayout): TenantPayout {
        val entity = jpa.findById(payout.id).orElse(null)?.apply {
            status = payout.status
            pivotRef = payout.pivotRef
            failureReason = payout.failureReason
        } ?: TenantPayoutJpaEntity(
            id = payout.id,
            kind = payout.kind,
            amountMinor = payout.amountMinor,
            channelCode = payout.channelCode,
            accountNumber = payout.accountNumber,
            accountName = payout.accountName,
            status = payout.status,
            pivotRef = payout.pivotRef,
            failureReason = payout.failureReason,
        )
        return jpa.save(entity).toDomain()
    }

    override fun list(): List<TenantPayout> =
        jpa.findAll().sortedByDescending { it.createdAt }.map { it.toDomain() }

    override fun findByReference(reference: String): TenantPayout? =
        jpa.findFirstByPivotRef(reference)?.toDomain()

    private fun TenantPayoutJpaEntity.toDomain(): TenantPayout = TenantPayout.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        kind = kind,
        amountMinor = amountMinor,
        channelCode = channelCode,
        accountNumber = accountNumber,
        accountName = accountName,
        status = status,
        pivotRef = pivotRef,
        failureReason = failureReason,
        createdAt = createdAt,
    )
}
