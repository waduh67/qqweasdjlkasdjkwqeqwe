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
    val amount: BigDecimal,
    status: SubscriptionInvoiceStatus,
    val issuedAt: Instant,
    val dueDate: LocalDate,
    paidAt: Instant?,
    gatewayProvider: String?,
    gatewayRef: String?,
    payUrl: String?,
) {
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

    /** Belum lunas & belum dibatalkan (ikut disweep scheduler untuk overdue/suspend). */
    val isOutstanding: Boolean
        get() = status == SubscriptionInvoiceStatus.ISSUED || status == SubscriptionInvoiceStatus.OVERDUE

    companion object {
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
        ): TenantSubscriptionInvoice = TenantSubscriptionInvoice(
            id, tenantId, subscriptionId, number, periodStart, periodEnd, amount, status,
            issuedAt, dueDate, paidAt, gatewayProvider, gatewayRef, payUrl,
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
