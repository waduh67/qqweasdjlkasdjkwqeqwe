package com.duluin.ftth.customer.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
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
 * Snapshot komersial sebuah paket ([com.duluin.ftth.catalog.Plan]) yang DIBEKUKAN ke
 * langganan saat create/update.
 *
 * Harga & atribut siklus disalin (bukan dibaca live) supaya invoice historis tetap
 * stabil walau harga paket berubah kelak. [planId] menyimpan asal paket untuk
 * penelusuran & agar bng bisa menyelaraskan sisi jaringan (grup RADIUS). Override
 * siklus bernilai null = ikut kebijakan billing global. Sisi jaringan (kecepatan,
 * burst, FUP) sengaja TIDAK di-snapshot — bng membacanya live dari catalog.
 */
data class PlanSnapshot(
    val planId: UUID?,
    val packageName: String,
    val bandwidthMbps: Int,
    val monthlyFee: BigDecimal,
    val prorateOnActivation: Boolean?,
    val billingDayOfMonth: Int?,
    val graceDays: Int?,
    val autoIsolir: Boolean?,
)

/**
 * Langganan layanan milik seorang pelanggan.
 *
 * Perpindahan status dijaga sebagai mesin keadaan eksplisit: langganan yang sudah
 * diakhiri tidak boleh "hidup lagi" diam-diam lewat update biasa, karena tanggal
 * aktivasi dan terminasi dipakai untuk penagihan. Detail paket disimpan sebagai
 * [PlanSnapshot] beku, bukan referensi hidup.
 */
class Subscription private constructor(
    val id: UUID,
    val tenantId: UUID,
    val customerId: UUID,
    planId: UUID?,
    packageName: String,
    bandwidthMbps: Int,
    monthlyFee: BigDecimal,
    prorateOnActivation: Boolean?,
    billingDayOfMonth: Int?,
    graceDays: Int?,
    autoIsolir: Boolean?,
    status: SubscriptionStatus,
    activatedAt: Instant?,
    terminatedAt: Instant?,
) {
    /** Asal paket katalog; null untuk langganan warisan sebelum sistem paket terpadu. */
    var planId: UUID? = planId
        private set

    var packageName: String = packageName
        private set

    var bandwidthMbps: Int = bandwidthMbps
        private set

    var monthlyFee: BigDecimal = monthlyFee
        private set

    var prorateOnActivation: Boolean? = prorateOnActivation
        private set

    var billingDayOfMonth: Int? = billingDayOfMonth
        private set

    var graceDays: Int? = graceDays
        private set

    var autoIsolir: Boolean? = autoIsolir
        private set

    var status: SubscriptionStatus = status
        private set

    var activatedAt: Instant? = activatedAt
        private set

    var terminatedAt: Instant? = terminatedAt
        private set

    /** Salin ulang snapshot paket (mis. ganti paket atau harga negosiasi). */
    fun updatePackage(snapshot: PlanSnapshot) {
        assertNotTerminated()
        val s = validate(snapshot)
        planId = s.planId
        packageName = s.packageName
        bandwidthMbps = s.bandwidthMbps
        monthlyFee = s.monthlyFee
        prorateOnActivation = s.prorateOnActivation
        billingDayOfMonth = s.billingDayOfMonth
        graceDays = s.graceDays
        autoIsolir = s.autoIsolir
    }

    /**
     * Setel langsung tanggal tagih (hari dalam bulan) — dipakai impor CSV yang membawa
     * `next_billing` sebagai basis siklus (pemetaan Opsi A: hanya harinya, di-clamp ≤28 oleh
     * pemanggil). null = kembalikan ke kebijakan billing global. Berbeda dari [updatePackage]
     * yang menyalin ulang seluruh snapshot; ini hanya menyentuh hari tagih.
     */
    fun overrideBillingDay(day: Int?) {
        assertNotTerminated()
        billingDayOfMonth = validateBillingDay(day)
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
            snapshot: PlanSnapshot,
        ): Subscription {
            val s = validate(snapshot)
            return Subscription(
                id = UuidV7.generate(),
                tenantId = tenantId,
                customerId = customerId,
                planId = s.planId,
                packageName = s.packageName,
                bandwidthMbps = s.bandwidthMbps,
                monthlyFee = s.monthlyFee,
                prorateOnActivation = s.prorateOnActivation,
                billingDayOfMonth = s.billingDayOfMonth,
                graceDays = s.graceDays,
                autoIsolir = s.autoIsolir,
                status = SubscriptionStatus.PENDING,
                activatedAt = null,
                terminatedAt = null,
            )
        }

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            customerId: UUID,
            planId: UUID?,
            packageName: String,
            bandwidthMbps: Int,
            monthlyFee: BigDecimal,
            prorateOnActivation: Boolean?,
            billingDayOfMonth: Int?,
            graceDays: Int?,
            autoIsolir: Boolean?,
            status: SubscriptionStatus,
            activatedAt: Instant?,
            terminatedAt: Instant?,
        ): Subscription = Subscription(
            id, tenantId, customerId, planId, packageName, bandwidthMbps, monthlyFee,
            prorateOnActivation, billingDayOfMonth, graceDays, autoIsolir,
            status, activatedAt, terminatedAt,
        )

        private fun validate(snapshot: PlanSnapshot): PlanSnapshot = snapshot.copy(
            packageName = validatePackageName(snapshot.packageName),
            bandwidthMbps = validateBandwidth(snapshot.bandwidthMbps),
            monthlyFee = validateFee(snapshot.monthlyFee),
            billingDayOfMonth = validateBillingDay(snapshot.billingDayOfMonth),
            graceDays = validateGraceDays(snapshot.graceDays),
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
            return fee.setScale(2, RoundingMode.HALF_UP)
        }

        private fun validateBillingDay(day: Int?): Int? {
            if (day != null && day !in 1..31) throw ValidationException("Tanggal tagih harus 1-31")
            return day
        }

        private fun validateGraceDays(days: Int?): Int? {
            if (days != null && days !in 0..90) throw ValidationException("Grace period harus 0-90 hari")
            return days
        }
    }
}
