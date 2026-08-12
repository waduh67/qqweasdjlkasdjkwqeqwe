package com.duluin.ftth.platformbilling.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Status langganan tenant ke aplikasi.
 *
 *  - ACTIVE    berjalan normal.
 *  - PAST_DUE  ada tagihan lewat jatuh tempo, masih dalam masa tenggang.
 *  - SUSPENDED lewat masa tenggang → konsol tenant jadi BACA-SAJA. Tenant-nya sendiri tetap
 *    aktif dan stafnya tetap bisa masuk: kalau tidak, orang yang hendak membayar tunggakannya
 *    justru terkunci di luar. Penegakannya di `AccessChecker` lewat `ReadOnlyLockGuard`.
 *  - CANCELLED dihentikan super-admin.
 */
enum class SubscriptionStatus { ACTIVE, PAST_DUE, SUSPENDED, CANCELLED }

/**
 * Langganan satu tenant ke aplikasi (SaaS) — flat fee bulanan. Satu baris per tenant.
 * [billingDay]/[graceDays] null = ikut default global ([PlatformSetting]). Perpindahan status
 * dijaga mesin keadaan eksplisit karena memicu efek nyata (auto-suspend saat menunggak,
 * auto-pulih saat lunas).
 */
class TenantSubscription private constructor(
    val id: UUID,
    val tenantId: UUID,
    monthlyFee: BigDecimal,
    status: SubscriptionStatus,
    billingDay: Int?,
    graceDays: Int?,
    currentPeriodStart: LocalDate?,
    currentPeriodEnd: LocalDate?,
    nextInvoiceAt: LocalDate?,
    activatedAt: Instant?,
) {
    var monthlyFee: BigDecimal = monthlyFee
        private set

    var status: SubscriptionStatus = status
        private set

    var billingDay: Int? = billingDay
        private set

    var graceDays: Int? = graceDays
        private set

    var currentPeriodStart: LocalDate? = currentPeriodStart
        private set

    var currentPeriodEnd: LocalDate? = currentPeriodEnd
        private set

    /** Tanggal saat scheduler boleh menerbitkan tagihan periode berikutnya. */
    var nextInvoiceAt: LocalDate? = nextInvoiceAt
        private set

    var activatedAt: Instant? = activatedAt
        private set

    /** Ubah biaya bulanan & default per-tenant (billing/grace). Tak mengubah status/periode. */
    fun configure(monthlyFee: BigDecimal, billingDay: Int?, graceDays: Int?) {
        this.monthlyFee = validateFee(monthlyFee)
        this.billingDay = billingDay?.also { validateBillingDay(it) }
        this.graceDays = graceDays?.also { if (it < 0) throw ValidationException("Masa tenggang tidak boleh negatif") }
    }

    /** Jadwalkan kapan tagihan berikutnya boleh terbit tanpa mengubah periode (mis. saat baru dibuat). */
    fun scheduleNextInvoice(date: LocalDate?) {
        this.nextInvoiceAt = date
    }

    /**
     * Beri masa aktif awal saat langganan baru dibuat: aktif sejak [start] selama sebulan, dan tagihan
     * PERTAMA baru terbit menjelang periode itu habis. Menghindari tenant baru langsung tertagih/tersuspend
     * di siklus scheduler berikutnya; perpanjangan setelahnya menambah masa aktif saat LUNAS.
     */
    fun seedInitialPeriod(start: LocalDate) {
        this.currentPeriodStart = start
        this.currentPeriodEnd = start.plusMonths(1)
        this.nextInvoiceAt = start.plusMonths(1)
    }

    /** Tandai periode berjalan + jadwal tagihan berikutnya (dipanggil generator saat menerbitkan). */
    fun openPeriod(start: LocalDate, end: LocalDate, nextInvoiceAt: LocalDate) {
        this.currentPeriodStart = start
        this.currentPeriodEnd = end
        this.nextInvoiceAt = nextInvoiceAt
    }

    /**
     * Perpanjang masa aktif [months] bulan saat sebuah tagihan LUNAS — inilah yang menambah
     * `currentPeriodEnd` (bukan penerbitan tagihan). Menumpuk di ujung bila masa aktif belum habis;
     * bila sudah lewat atau belum pernah aktif, mulai periode baru dari [today]. [months] > 1 dipakai
     * saat tenant membayar di muka beberapa bulan sekaligus. Tak berlaku bila langganan CANCELLED.
     */
    fun extendOnPayment(today: LocalDate, months: Long = 1) {
        if (status == SubscriptionStatus.CANCELLED) return
        val span = months.coerceAtLeast(1)
        val end = currentPeriodEnd
        if (end == null || end.isBefore(today)) {
            currentPeriodStart = today
            currentPeriodEnd = today.plusMonths(span)
        } else {
            currentPeriodEnd = end.plusMonths(span)
        }
        if (activatedAt == null) activatedAt = Instant.now()
    }

    /**
     * Setelah bonus bulan gratis "dilunasi": geser jadwal tagihan berikutnya ke ujung masa aktif baru
     * agar scheduler tak menagih selama masa bonus. Konvensinya sama dengan [seedInitialPeriod] —
     * jadwal tagih = akhir masa aktif. Masa aktifnya sendiri tetap hanya ditambah [extendOnPayment].
     */
    fun deferNextInvoiceToPeriodEnd() {
        nextInvoiceAt = currentPeriodEnd
    }

    /** Ada tagihan lewat jatuh tempo (masih dalam grace). Idempoten; tak berlaku bila CANCELLED. */
    fun markPastDue() {
        if (status == SubscriptionStatus.CANCELLED || status == SubscriptionStatus.SUSPENDED) return
        status = SubscriptionStatus.PAST_DUE
    }

    /** Lewat masa tenggang → langganan disuspend, dan konsol tenant jadi baca-saja. */
    fun suspend() {
        if (status == SubscriptionStatus.CANCELLED) {
            throw ValidationException("Langganan yang dibatalkan tidak bisa disuspend")
        }
        status = SubscriptionStatus.SUSPENDED
    }

    /** Pulihkan ke ACTIVE saat tunggakan lunas — kunci baca-saja ikut terbuka. Idempoten. */
    fun activate() {
        if (status == SubscriptionStatus.CANCELLED) {
            throw ValidationException("Langganan yang dibatalkan tidak bisa diaktifkan; buat langganan baru")
        }
        if (activatedAt == null) activatedAt = Instant.now()
        status = SubscriptionStatus.ACTIVE
    }

    /** Hentikan langganan (super-admin). Berhenti ditagih. */
    fun cancel() {
        status = SubscriptionStatus.CANCELLED
        nextInvoiceAt = null
    }

    val isCancelled: Boolean get() = status == SubscriptionStatus.CANCELLED

    companion object {
        fun create(
            tenantId: UUID,
            monthlyFee: BigDecimal,
            billingDay: Int? = null,
            graceDays: Int? = null,
        ): TenantSubscription = TenantSubscription(
            id = UuidV7.generate(),
            tenantId = tenantId,
            monthlyFee = validateFee(monthlyFee),
            status = SubscriptionStatus.ACTIVE,
            billingDay = billingDay?.also { validateBillingDay(it) },
            graceDays = graceDays,
            currentPeriodStart = null,
            currentPeriodEnd = null,
            nextInvoiceAt = null,
            activatedAt = Instant.now(),
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            monthlyFee: BigDecimal,
            status: SubscriptionStatus,
            billingDay: Int?,
            graceDays: Int?,
            currentPeriodStart: LocalDate?,
            currentPeriodEnd: LocalDate?,
            nextInvoiceAt: LocalDate?,
            activatedAt: Instant?,
        ): TenantSubscription = TenantSubscription(
            id, tenantId, monthlyFee, status, billingDay, graceDays,
            currentPeriodStart, currentPeriodEnd, nextInvoiceAt, activatedAt,
        )

        private fun validateFee(fee: BigDecimal): BigDecimal {
            if (fee.signum() < 0) throw ValidationException("Biaya bulanan tidak boleh negatif")
            return fee.setScale(2, RoundingMode.HALF_UP)
        }

        private fun validateBillingDay(day: Int) {
            if (day !in 1..28) throw ValidationException("Tanggal tagih harus 1-28")
        }
    }
}
