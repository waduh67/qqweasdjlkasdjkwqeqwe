package com.duluin.ftth.hotspot.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class VoucherStatus { AVAILABLE, ACTIVE, EXPIRED, REVOKED }
enum class VoucherBatchStatus { OPEN, CLOSED }

class Voucher private constructor(
    val id: UUID,
    val tenantId: UUID,
    val batchId: UUID?,
    val username: String,
    val siteId: UUID,
    val planId: UUID,
    val duration: Duration,
    var status: VoucherStatus,
    var activatedAt: Instant?,
    var expiresAt: Instant?,
    var deviceId: String?,
    var revokedAt: Instant?,
    var revokedBy: UUID?,
    var revocationReason: String?,
) {
    init {
        require(username == canonicalUsername(username)) { "Username voucher tidak valid" }
        require(!duration.isZero && !duration.isNegative) { "Durasi voucher harus lebih dari nol" }
    }

    @Synchronized
    fun claim(deviceId: String, clock: Clock): Voucher {
        val normalizedDevice = requireDeviceId(deviceId)
        val now = clock.instant()
        expireIfDue(now)
        when (status) {
            VoucherStatus.AVAILABLE -> {
                status = VoucherStatus.ACTIVE
                activatedAt = now
                expiresAt = now.plus(duration)
                this.deviceId = normalizedDevice
            }
            VoucherStatus.ACTIVE -> {
                if (this.deviceId != normalizedDevice) throw ConflictException("Voucher sudah terikat ke perangkat lain")
            }
            VoucherStatus.EXPIRED -> throw ConflictException("Voucher sudah kedaluwarsa")
            VoucherStatus.REVOKED -> throw ConflictException("Voucher sudah dicabut")
        }
        return this
    }

    fun revoke(revokedBy: UUID, reason: String, clock: Clock) {
        require(reason.isNotBlank() && reason.length <= 500) { "Alasan pencabutan tidak valid" }
        if (status == VoucherStatus.REVOKED) return
        expireIfDue(clock.instant())
        status = VoucherStatus.REVOKED
        revokedAt = clock.instant()
        this.revokedBy = revokedBy
        revocationReason = reason.trim()
    }

    fun expireIfDue(now: Instant): Boolean {
        if (status == VoucherStatus.ACTIVE && expiresAt?.let { !now.isBefore(it) } == true) {
            status = VoucherStatus.EXPIRED
            return true
        }
        return false
    }

    companion object {
        fun create(
            tenantId: UUID,
            batchId: UUID?,
            username: String,
            password: String,
            siteId: UUID,
            planId: UUID,
            duration: Duration,
        ): Voucher {
            require(password.isNotBlank()) { "Password voucher wajib diisi" }
            return Voucher(UuidV7.generate(), tenantId, batchId, canonicalUsername(username), siteId, planId, duration,
                VoucherStatus.AVAILABLE, null, null, null, null, null, null)
        }

        fun rehydrate(
            id: UUID, tenantId: UUID, batchId: UUID?, username: String, siteId: UUID, planId: UUID,
            duration: Duration, status: VoucherStatus, activatedAt: Instant?, expiresAt: Instant?, deviceId: String?,
            revokedAt: Instant?, revokedBy: UUID?, revocationReason: String?,
        ) = Voucher(id, tenantId, batchId, username, siteId, planId, duration, status, activatedAt, expiresAt,
            deviceId, revokedAt, revokedBy, revocationReason)

        fun canonicalUsername(value: String): String {
            val canonical = value.trim().uppercase(java.util.Locale.ROOT)
            require(canonical.matches(Regex("[A-Z0-9][A-Z0-9_-]{2,63}"))) { "Username voucher tidak valid" }
            return canonical
        }

        private fun requireDeviceId(value: String): String {
            val normalized = value.trim()
            if (normalized.isBlank() || normalized.length > 255) throw ValidationException("Identitas perangkat tidak valid")
            return normalized
        }
    }
}

class VoucherBatch private constructor(
    val id: UUID,
    val tenantId: UUID,
    val siteId: UUID,
    val planId: UUID,
    val duration: Duration,
    var status: VoucherBatchStatus,
) {
    fun close() { status = VoucherBatchStatus.CLOSED }

    companion object {
        fun create(tenantId: UUID, siteId: UUID, planId: UUID, duration: Duration): VoucherBatch {
            require(!duration.isZero && !duration.isNegative) { "Durasi voucher harus lebih dari nol" }
            return VoucherBatch(UuidV7.generate(), tenantId, siteId, planId, duration, VoucherBatchStatus.OPEN)
        }
        fun rehydrate(id: UUID, tenantId: UUID, siteId: UUID, planId: UUID, duration: Duration, status: VoucherBatchStatus) =
            VoucherBatch(id, tenantId, siteId, planId, duration, status)
    }
}
