package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.domain.model.PayoutKind
import com.duluin.ftth.billing.domain.model.PayoutStatus
import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/**
 * Riwayat penyaluran dana per-tenant (tenant-scoped + RLS). Jejak audit finansial — nominal
 * minor-unit IDR. Semua kolom NON-rahasia.
 */
@Entity
@Table(name = "tenant_payout")
class TenantPayoutJpaEntity(
    id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    var kind: PayoutKind,

    @Column(name = "amount_minor", nullable = false)
    var amountMinor: Long,

    @Column(name = "channel_code", length = 40)
    var channelCode: String?,

    @Column(name = "account_number", length = 60)
    var accountNumber: String?,

    @Column(name = "account_name", length = 160)
    var accountName: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: PayoutStatus,

    @Column(name = "pivot_ref", length = 128)
    var pivotRef: String?,

    @Column(name = "failure_reason", length = 500)
    var failureReason: String?,
) : TenantAwareJpaEntity(id)
