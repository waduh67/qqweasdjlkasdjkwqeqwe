package com.duluin.ftth.customer.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class SubscriptionStatus {
    /** Sudah dijual, menunggu instalasi. */
    PENDING,
    ACTIVE,
    /** Isolir sementara — layanan dimatikan, kontrak masih berjalan. */
    ISOLATED,
    TERMINATED,
}

/**
 * Langganan layanan milik seorang pelanggan.
 *
 * Perpindahan status dijaga sebagai mesin keadaan eksplisit: langganan yang sudah
 * diakhiri tidak boleh "hidup lagi" diam-diam lewat update biasa, karena tanggal
 * aktivasi dan terminasi dipakai untuk penagihan.
 */
class Subscription private constructor(
    val id: UUID,
    val tenantId: UUID,
    val customerId: UUID,
    packageName: String,
    bandwidthMbps: Int,
    monthlyFee: BigDecimal,
    status: SubscriptionStatus,
    activatedAt: Instant?,
    terminatedAt: Instant?,
) {
    var packageName: String = packageName
        private set

    var bandwidthMbps: Int = bandwidthMbps
        private set

    var monthlyFee: BigDecimal = monthlyFee
        private set

    var status: SubscriptionStatus = status
        private set

    var activatedAt: Instant? = activatedAt
        private set

    var terminatedAt: Instant? = terminatedAt
        private set

    fun updatePackage(packageName: String, bandwidthMbps: Int, monthlyFee: BigDecimal) {
        assertNotTerminated()
        this.packageName = validatePackageName(packageName)
        this.bandwidthMbps = validateBandwidth(bandwidthMbps)
        this.monthlyFee = validateFee(monthlyFee)
    }

    fun activate(at: Instant = Instant.now()) {
        assertNotTerminated()
        status = SubscriptionStatus.ACTIVE
        // Tanggal aktivasi pertama dipertahankan; reaktivasi setelah isolir bukan
        // pelanggan baru, dan menimpanya akan merusak riwayat penagihan.
        if (activatedAt == null) activatedAt = at
    }

    fun isolate() {
        assertNotTerminated()
        if (status != SubscriptionStatus.ACTIVE) {
            throw ConflictException("Hanya langganan aktif yang bisa diisolir (status sekarang: $status)")
        }
        status = SubscriptionStatus.ISOLATED
    }

    fun terminate(at: Instant = Instant.now()) {
        if (status == SubscriptionStatus.TERMINATED) return
        status = SubscriptionStatus.TERMINATED
        terminatedAt = at
    }

    private fun assertNotTerminated() {
        if (status == SubscriptionStatus.TERMINATED) {
            throw ConflictException("Langganan sudah diakhiri dan tidak bisa diubah lagi")
        }
    }

    companion object {
        fun create(
            tenantId: UUID,
            customerId: UUID,
            packageName: String,
            bandwidthMbps: Int,
            monthlyFee: BigDecimal,
        ): Subscription = Subscription(
            id = UuidV7.generate(),
            tenantId = tenantId,
            customerId = customerId,
            packageName = validatePackageName(packageName),
            bandwidthMbps = validateBandwidth(bandwidthMbps),
            monthlyFee = validateFee(monthlyFee),
            status = SubscriptionStatus.PENDING,
            activatedAt = null,
            terminatedAt = null,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            customerId: UUID,
            packageName: String,
            bandwidthMbps: Int,
            monthlyFee: BigDecimal,
            status: SubscriptionStatus,
            activatedAt: Instant?,
            terminatedAt: Instant?,
        ): Subscription = Subscription(
            id, tenantId, customerId, packageName, bandwidthMbps, monthlyFee, status, activatedAt, terminatedAt,
        )

        private fun validatePackageName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.length !in 2..100) throw ValidationException("Nama paket harus 2-100 karakter")
            return trimmed
        }

        private fun validateBandwidth(mbps: Int): Int {
            if (mbps !in 1..100_000) throw ValidationException("Bandwidth harus 1-100000 Mbps")
            return mbps
        }

        private fun validateFee(fee: BigDecimal): BigDecimal {
            if (fee.signum() < 0) throw ValidationException("Biaya bulanan tidak boleh negatif")
            return fee.setScale(2, java.math.RoundingMode.HALF_UP)
        }
    }
}
