package com.duluin.ftth.hotspot.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.hotspot.domain.model.VoucherBatchStatus
import com.duluin.ftth.hotspot.domain.model.VoucherStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "hotspot_voucher_batch")
class VoucherBatchJpaEntity(
    id: UUID,
    @Column(name = "site_id", nullable = false, updatable = false) var siteId: UUID,
    @Column(name = "plan_id", nullable = false, updatable = false) var planId: UUID,
    @Column(name = "duration_seconds", nullable = false, updatable = false) var durationSeconds: Long,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var status: VoucherBatchStatus,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "hotspot_voucher")
class VoucherJpaEntity(
    id: UUID,
    @Column(name = "batch_id", updatable = false) var batchId: UUID?,
    @Column(nullable = false, updatable = false, length = 64) var username: String,
    @Column(name = "password_ciphertext", nullable = false, length = 512) var passwordCiphertext: String,
    @Column(name = "site_id", nullable = false, updatable = false) var siteId: UUID,
    @Column(name = "plan_id", nullable = false, updatable = false) var planId: UUID,
    @Column(name = "duration_seconds", nullable = false, updatable = false) var durationSeconds: Long,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var status: VoucherStatus,
    @Column(name = "activated_at") var activatedAt: Instant?,
    @Column(name = "expires_at") var expiresAt: Instant?,
    @Column(name = "device_id", length = 255) var deviceId: String?,
    @Column(name = "revoked_at") var revokedAt: Instant?,
    @Column(name = "revoked_by") var revokedBy: UUID?,
    @Column(name = "revocation_reason", length = 500) var revocationReason: String?,
) : TenantAwareJpaEntity(id)
