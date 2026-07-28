package com.duluin.ftth.billing.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Daur hidup sebuah tagihan.
 *
 * ISSUED → PAID (lunas) atau OVERDUE (lewat jatuh tempo) → PAID; VOID untuk yang
 * dibatalkan. Perpindahannya dijaga sebagai mesin keadaan eksplisit karena
 * memicu efek nyata (auto-isolir saat menunggak, auto-pulih saat lunas).
 */
enum class InvoiceStatus { ISSUED, PAID, OVERDUE, VOID }

/**
 * Hasil perhitungan prorata: [amount] yang harus ditagih (skala 2) untuk [days] hari
 * terpakai — hari aktivasi sampai akhir periode, inklusif.
 */
data class Proration(val amount: BigDecimal, val days: Int)

/**
 * Tagihan satu periode langganan seorang pelanggan.
 *
 * Ditaut ke `subscription`/`customer` (module customer) lewat UUID polos tanpa FK
 * lintas-module. [amount] disimpan pada skala 2 (rupiah bulat + sen). Referensi
 * gateway ([gatewayProvider]/[gatewayRef]/[payUrl]) dilekatkan setelah charge dibuat
 * dan tidak pernah mengubah nilai tagihan.
 */
class Invoice private constructor(
    val id: UUID,
    val tenantId: UUID,
    val customerId: UUID,
    val subscriptionId: UUID,
    /** Nomor tagihan unik per tenant, mis. `INV-202607-0001`; tak berubah. */
    val number: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val amount: BigDecimal,
    /** Tagihan ini diprorata (aktivasi tengah periode) — [amount] < tarif penuh sebulan. */
    val prorated: Boolean,
    /** Jumlah hari terpakai yang ditagihkan bila [prorated]; null saat tagihan penuh. */
    val proratedDays: Int?,
    status: InvoiceStatus,
    val issuedAt: Instant,
    val dueDate: LocalDate,
    paidAt: Instant?,
    gatewayProvider: String?,
    gatewayRef: String?,
    payUrl: String?,
) {
    var status: InvoiceStatus = status
        private set

    var paidAt: Instant? = paidAt
        private set

    var gatewayProvider: String? = gatewayProvider
        private set

    var gatewayRef: String? = gatewayRef
        private set

    var payUrl: String? = payUrl
        private set

    /**
     * Tandai lunas. Idempoten: pemanggilan ulang atas tagihan yang sudah PAID tidak
     * berdampak (callback gateway bisa datang berkali-kali). Tagihan VOID ditolak —
     * pembayaran atas tagihan yang dibatalkan adalah anomali yang harus terlihat.
     */
    fun markPaid(at: Instant) {
        when (status) {
            InvoiceStatus.PAID -> return
            InvoiceStatus.VOID -> throw ConflictException("Tagihan yang dibatalkan tidak bisa ditandai lunas")
            InvoiceStatus.ISSUED, InvoiceStatus.OVERDUE -> {
                status = InvoiceStatus.PAID
                paidAt = at
            }
        }
    }

    /** Hanya tagihan yang masih terbit (ISSUED) yang bisa jatuh tempo. */
    fun markOverdue() {
        if (status != InvoiceStatus.ISSUED) {
            throw ConflictException("Hanya tagihan terbit yang bisa jatuh tempo (status sekarang: $status)")
        }
        status = InvoiceStatus.OVERDUE
    }

    /** Batalkan tagihan; ditolak bila sudah lunas agar riwayat pembayaran tak hilang. */
    fun void() {
        if (status == InvoiceStatus.PAID) {
            throw ConflictException("Tagihan yang sudah lunas tidak bisa dibatalkan")
        }
        status = InvoiceStatus.VOID
    }

    /** Lekatkan hasil charge gateway (referensi & tautan bayar) tanpa mengubah nilai. */
    fun attachCharge(provider: String?, gatewayRef: String?, payUrl: String?) {
        this.gatewayProvider = provider
        this.gatewayRef = gatewayRef
        this.payUrl = payUrl
    }

    companion object {
        @Suppress("LongParameterList")
        fun create(
            tenantId: UUID,
            customerId: UUID,
            subscriptionId: UUID,
            number: String,
            periodStart: LocalDate,
            periodEnd: LocalDate,
            amount: BigDecimal,
            dueDate: LocalDate,
            prorated: Boolean = false,
            proratedDays: Int? = null,
        ): Invoice = Invoice(
            id = UuidV7.generate(),
            tenantId = tenantId,
            customerId = customerId,
            subscriptionId = subscriptionId,
            number = validateNumber(number),
            periodStart = periodStart,
            periodEnd = periodEnd,
            amount = validateAmount(amount),
            prorated = prorated,
            proratedDays = validateProratedDays(prorated, proratedDays),
            status = InvoiceStatus.ISSUED,
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
            customerId: UUID,
            subscriptionId: UUID,
            number: String,
            periodStart: LocalDate,
            periodEnd: LocalDate,
            amount: BigDecimal,
            prorated: Boolean,
            proratedDays: Int?,
            status: InvoiceStatus,
            issuedAt: Instant,
            dueDate: LocalDate,
            paidAt: Instant?,
            gatewayProvider: String?,
            gatewayRef: String?,
            payUrl: String?,
        ): Invoice = Invoice(
            id, tenantId, customerId, subscriptionId, number, periodStart, periodEnd, amount,
            prorated, proratedDays, status, issuedAt, dueDate, paidAt, gatewayProvider, gatewayRef, payUrl,
        )

        /**
         * Hitung prorata bila [activationDate] jatuh di dalam [periodStart]..[periodEnd]
         * dan bukan hari pertama periode. Pelanggan hanya membayar hari terpakai
         * (hari aktivasi s/d akhir periode, inklusif). Mengembalikan null → tagih penuh:
         * aktivasi di luar periode (bulan berikutnya sudah ditagih penuh) atau tepat di
         * awal periode (sudah sebulan penuh, bukan prorata).
         */
        fun prorate(
            fullAmount: BigDecimal,
            activationDate: LocalDate,
            periodStart: LocalDate,
            periodEnd: LocalDate,
        ): Proration? {
            if (activationDate.isBefore(periodStart) || activationDate.isAfter(periodEnd)) return null
            val daysInPeriod = ChronoUnit.DAYS.between(periodStart, periodEnd).toInt() + 1
            val usedDays = ChronoUnit.DAYS.between(activationDate, periodEnd).toInt() + 1
            if (usedDays >= daysInPeriod) return null
            val amount = fullAmount
                .multiply(BigDecimal(usedDays))
                .divide(BigDecimal(daysInPeriod), 2, RoundingMode.HALF_UP)
            return Proration(amount, usedDays)
        }

        private fun validateNumber(number: String): String {
            val trimmed = number.trim()
            if (trimmed.isBlank()) throw ValidationException("Nomor tagihan wajib diisi")
            return trimmed
        }

        private fun validateAmount(amount: BigDecimal): BigDecimal {
            if (amount.signum() < 0) throw ValidationException("Nilai tagihan tidak boleh negatif")
            return amount.setScale(2, RoundingMode.HALF_UP)
        }

        /** Hari prorata wajib >= 1 saat diprorata; diabaikan (null) saat tagihan penuh. */
        private fun validateProratedDays(prorated: Boolean, days: Int?): Int? {
            if (!prorated) return null
            if (days == null || days < 1) throw ValidationException("Hari prorata harus >= 1 saat tagihan diprorata")
            return days
        }
    }
}
