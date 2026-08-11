package com.duluin.ftth.platformbilling.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Daur hidup tagihan langganan: ISSUED → PAID | OVERDUE → PAID; VOID untuk yang dibatalkan. */
enum class SubscriptionInvoiceStatus { ISSUED, PAID, OVERDUE, VOID }

/**
 * Tagihan satu periode langganan sebuah tenant ke aplikasi. Nomor unik `SUB-<yyyymm>-<tenant8>`.
 * [amount] skala 2. Referensi gateway ([gatewayProvider]/[gatewayRef]/[payUrl]) dilekatkan setelah
 * charge dibuat dan tidak pernah mengubah nilai. Mirror `billing.domain.model.Invoice` tanpa
 * proration/customer.
 */
class TenantSubscriptionInvoice private constructor(
    val id: UUID,
    val tenantId: UUID,
    val subscriptionId: UUID,
    val number: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    amount: BigDecimal,
    status: SubscriptionInvoiceStatus,
    val issuedAt: Instant,
    val dueDate: LocalDate,
    paidAt: Instant?,
    gatewayProvider: String?,
    gatewayRef: String?,
    payUrl: String?,
    payMethod: String?,
    vaChannel: String?,
    vaNumber: String?,
    vaName: String?,
    vaExpiresAt: Instant?,
    qrContent: String?,
    qrUrl: String?,
    qrExpiresAt: Instant?,
) {
    /** Nilai tagihan (skala 2). Umumnya tetap; hanya diubah lewat [reprice] sebelum di-charge. */
    var amount: BigDecimal = amount
        private set

    var status: SubscriptionInvoiceStatus = status
        private set

    var paidAt: Instant? = paidAt
        private set

    var gatewayProvider: String? = gatewayProvider
        private set

    var gatewayRef: String? = gatewayRef
        private set

    var payUrl: String? = payUrl
        private set

    /** Instrumen bayar in-app terpilih (VIRTUAL_ACCOUNT/QR) & instruksinya (nomor VA / string QRIS). */
    var payMethod: String? = payMethod
        private set

    var vaChannel: String? = vaChannel
        private set

    var vaNumber: String? = vaNumber
        private set

    var vaName: String? = vaName
        private set

    var vaExpiresAt: Instant? = vaExpiresAt
        private set

    var qrContent: String? = qrContent
        private set

    var qrUrl: String? = qrUrl
        private set

    var qrExpiresAt: Instant? = qrExpiresAt
        private set

    /** Tandai lunas. Idempoten (callback bisa datang berkali-kali). Tagihan VOID ditolak. */
    fun markPaid(at: Instant) {
        when (status) {
            SubscriptionInvoiceStatus.PAID -> return
            SubscriptionInvoiceStatus.VOID -> throw ConflictException("Tagihan yang dibatalkan tidak bisa ditandai lunas")
            SubscriptionInvoiceStatus.ISSUED, SubscriptionInvoiceStatus.OVERDUE -> {
                status = SubscriptionInvoiceStatus.PAID
                paidAt = at
            }
        }
    }

    /** Hanya tagihan terbit (ISSUED) yang bisa jatuh tempo. */
    fun markOverdue() {
        if (status != SubscriptionInvoiceStatus.ISSUED) {
            throw ConflictException("Hanya tagihan terbit yang bisa jatuh tempo (status sekarang: $status)")
        }
        status = SubscriptionInvoiceStatus.OVERDUE
    }

    /** Batalkan tagihan; ditolak bila sudah lunas agar riwayat pembayaran tak hilang. */
    fun void() {
        if (status == SubscriptionInvoiceStatus.PAID) {
            throw ConflictException("Tagihan yang sudah lunas tidak bisa dibatalkan")
        }
        status = SubscriptionInvoiceStatus.VOID
    }

    /** Lekatkan hasil charge gateway tanpa mengubah nilai. */
    fun attachCharge(provider: String?, gatewayRef: String?, payUrl: String?) {
        this.gatewayProvider = provider
        this.gatewayRef = gatewayRef
        this.payUrl = payUrl
    }

    /**
     * Lekatkan instruksi bayar in-app (mode API) — nomor VA atau string QRIS — tanpa mengubah nilai.
     * Membuang [payUrl] (alur in-app tak me-redirect); menimpa instruksi lama saat tenant mengganti
     * instrumen (VA↔QRIS).
     */
    @Suppress("LongParameterList")
    fun attachInstruction(
        provider: String?,
        gatewayRef: String?,
        method: String?,
        vaChannel: String?,
        vaNumber: String?,
        vaName: String?,
        vaExpiresAt: Instant?,
        qrContent: String?,
        qrUrl: String?,
        qrExpiresAt: Instant?,
    ) {
        this.gatewayProvider = provider
        this.gatewayRef = gatewayRef
        this.payUrl = null
        this.payMethod = method
        this.vaChannel = vaChannel
        this.vaNumber = vaNumber
        this.vaName = vaName
        this.vaExpiresAt = vaExpiresAt
        this.qrContent = qrContent
        this.qrUrl = qrUrl
        this.qrExpiresAt = qrExpiresAt
    }

    /** Belum lunas & belum dibatalkan (ikut disweep scheduler untuk overdue/suspend). */
    val isOutstanding: Boolean
        get() = status == SubscriptionInvoiceStatus.ISSUED || status == SubscriptionInvoiceStatus.OVERDUE

    /**
     * Tagihan bonus bulan gratis, dikenali dari awalan nomornya ([GRANT_PREFIX]) — nilainya Rp 0 dan
     * langsung lunas, jadi tak pernah ditagihkan ke tenant.
     */
    val isGrant: Boolean
        get() = number.startsWith(GRANT_PREFIX)

    /**
     * Sesuaikan nilai mengikuti biaya bulanan baru — HANYA untuk tagihan belum lunas yang BELUM
     * di-charge (tanpa instrumen bayar terlekat) agar nilai lokal tak desync dari nominal gateway.
     * Mengembalikan true bila nilai berubah (pemanggil menyimpan), false bila dilewati/tak berubah.
     */
    fun reprice(newAmount: BigDecimal): Boolean {
        if (!isOutstanding) return false
        if (gatewayRef != null || payMethod != null) return false
        val normalized = validateAmount(newAmount)
        if (normalized.compareTo(amount) == 0) return false
        amount = normalized
        return true
    }

    companion object {
        /** Awalan nomor tagihan bonus bulan gratis (`FREE-<yyyymm>-<tenant8>`), lawan `SUB-` biasa. */
        const val GRANT_PREFIX = "FREE-"

        fun create(
            tenantId: UUID,
            subscriptionId: UUID,
            number: String,
            periodStart: LocalDate,
            periodEnd: LocalDate,
            amount: BigDecimal,
            dueDate: LocalDate,
        ): TenantSubscriptionInvoice = TenantSubscriptionInvoice(
            id = UuidV7.generate(),
            tenantId = tenantId,
            subscriptionId = subscriptionId,
            number = validateNumber(number),
            periodStart = periodStart,
            periodEnd = periodEnd,
            amount = validateAmount(amount),
            status = SubscriptionInvoiceStatus.ISSUED,
            issuedAt = Instant.now(),
            dueDate = dueDate,
            paidAt = null,
            gatewayProvider = null,
            gatewayRef = null,
            payUrl = null,
            payMethod = null,
            vaChannel = null,
            vaNumber = null,
            vaName = null,
            vaExpiresAt = null,
            qrContent = null,
            qrUrl = null,
            qrExpiresAt = null,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            subscriptionId: UUID,
            number: String,
            periodStart: LocalDate,
            periodEnd: LocalDate,
            amount: BigDecimal,
            status: SubscriptionInvoiceStatus,
            issuedAt: Instant,
            dueDate: LocalDate,
            paidAt: Instant?,
            gatewayProvider: String?,
            gatewayRef: String?,
            payUrl: String?,
            payMethod: String?,
            vaChannel: String?,
            vaNumber: String?,
            vaName: String?,
            vaExpiresAt: Instant?,
            qrContent: String?,
            qrUrl: String?,
            qrExpiresAt: Instant?,
        ): TenantSubscriptionInvoice = TenantSubscriptionInvoice(
            id, tenantId, subscriptionId, number, periodStart, periodEnd, amount, status,
            issuedAt, dueDate, paidAt, gatewayProvider, gatewayRef, payUrl, payMethod,
            vaChannel, vaNumber, vaName, vaExpiresAt, qrContent, qrUrl, qrExpiresAt,
        )

        private fun validateNumber(number: String): String {
            val trimmed = number.trim()
            if (trimmed.isBlank()) throw ValidationException("Nomor tagihan wajib diisi")
            return trimmed
        }

        private fun validateAmount(amount: BigDecimal): BigDecimal {
            if (amount.signum() < 0) throw ValidationException("Nilai tagihan tidak boleh negatif")
            return amount.setScale(2, RoundingMode.HALF_UP)
        }
    }
}
