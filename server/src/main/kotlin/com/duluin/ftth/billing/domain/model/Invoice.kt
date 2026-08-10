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
 *
 * REFUNDED = sudah lunas lalu uangnya dikembalikan PENUH. Statusnya sendiri, bukan VOID:
 * tagihan yang dibatalkan tak pernah menghasilkan uang, sedangkan yang direfund menghasilkan
 * lalu mengembalikannya — dua hal yang harus bisa dibedakan di laporan pendapatan. Refund
 * SEBAGIAN membiarkan tagihan tetap PAID (lihat [Invoice.refundedAmount]).
 */
enum class InvoiceStatus { ISSUED, PAID, OVERDUE, VOID, REFUNDED }

/**
 * Hasil perhitungan prorata: [amount] yang harus ditagih (skala 2) untuk [days] hari
 * terpakai — hari aktivasi sampai akhir periode, inklusif.
 */
data class Proration(val amount: BigDecimal, val days: Int)

/**
 * Tagihan satu periode langganan seorang pelanggan.
 *
 * Ditaut ke `subscription`/`customer` (module customer) lewat UUID polos tanpa FK
 * lintas-module. [amount] adalah TOTAL yang ditagih ke pelanggan (skala 2, rupiah bulat +
 * sen) — sudah termasuk [taxAmount] (PPN). Dasar sebelum pajak (DPP) = [baseAmount].
 * PPN nol saat tenant tak mengaktifkannya, sehingga tagihan lama (tanpa kolom pajak) tetap
 * setara total = dasar. Referensi gateway ([gatewayProvider]/[gatewayRef]/[payUrl])
 * dilekatkan setelah charge dibuat dan tidak pernah mengubah nilai tagihan.
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
    /** Komponen PPN (skala 2) yang SUDAH termasuk dalam [amount]; nol bila tagihan tanpa PPN. */
    val taxAmount: BigDecimal,
    /** Tarif PPN yang diterapkan (mis. 0.1100); null bila tagihan tanpa PPN. */
    val taxRate: BigDecimal?,
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
    payMethod: String?,
    vaChannel: String?,
    vaNumber: String?,
    vaName: String?,
    vaExpiresAt: Instant?,
    qrContent: String?,
    qrUrl: String?,
    qrExpiresAt: Instant?,
    dueSoonReminded: Boolean,
    refundedAmount: BigDecimal = ZERO_MONEY,
) {
    /** Dasar Pengenaan Pajak (DPP): nilai layanan sebelum PPN = [amount] − [taxAmount]. */
    val baseAmount: BigDecimal get() = amount.subtract(taxAmount)

    var status: InvoiceStatus = status
        private set

    /**
     * Sudah pernah dikirimi pengingat "mendekati jatuh tempo" — penjaga idempoten
     * agar sweep pengingat berkala (tiap 12 jam) tak mengirimi pelanggan berkali-kali
     * untuk tagihan yang sama. Sekali dinyalakan tak pernah mati (tagihan tak terbit ulang).
     */
    var dueSoonReminded: Boolean = dueSoonReminded
        private set

    var paidAt: Instant? = paidAt
        private set

    /**
     * Total uang yang SUDAH benar-benar kembali ke pelanggan atas tagihan ini (skala 2). Hanya
     * pengembalian yang berhasil yang dijumlah di sini — permintaan yang masih berjalan ditahan
     * di baris [Refund], bukan di tagihan, supaya angka ini selalu bisa dibaca apa adanya:
     * "sebanyak inilah yang bukan lagi pendapatan".
     */
    var refundedAmount: BigDecimal = refundedAmount
        private set

    /** Sisa yang masih boleh dikembalikan = [amount] − [refundedAmount]; tak pernah negatif. */
    val refundableAmount: BigDecimal get() = amount.subtract(refundedAmount).max(ZERO_MONEY)

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

    /**
     * Tandai lunas. Idempoten: pemanggilan ulang atas tagihan yang sudah PAID tidak
     * berdampak (callback gateway bisa datang berkali-kali). Tagihan VOID ditolak —
     * pembayaran atas tagihan yang dibatalkan adalah anomali yang harus terlihat.
     */
    fun markPaid(at: Instant) {
        when (status) {
            InvoiceStatus.PAID -> return
            InvoiceStatus.VOID -> throw ConflictException("Tagihan yang dibatalkan tidak bisa ditandai lunas")
            // Sudah lunas lalu dikembalikan penuh: kalau pelanggan membayar lagi, itu tagihan/
            // pembayaran baru — menimpanya jadi PAID akan menyembunyikan refund yang sudah terjadi.
            InvoiceStatus.REFUNDED ->
                throw ConflictException("Tagihan yang sudah dikembalikan tidak bisa ditandai lunas lagi")
            InvoiceStatus.ISSUED, InvoiceStatus.OVERDUE -> {
                status = InvoiceStatus.PAID
                paidAt = at
            }
        }
    }

    /**
     * Catat bahwa [amount] rupiah benar-benar kembali ke pelanggan. Menaikkan [refundedAmount] dan —
     * hanya bila seluruh nilai tagihan sudah kembali — memindahkan status ke REFUNDED. Refund
     * sebagian membiarkan status PAID: layanannya tetap terbayar, cuma sebagian uangnya balik.
     *
     * Menolak melampaui nilai tagihan; batas ini ditegakkan di sini (bukan cuma di service) karena
     * mengembalikan lebih dari yang diterima adalah kebocoran uang, bukan sekadar data janggal.
     */
    fun applyRefund(amount: BigDecimal) {
        if (amount.signum() <= 0) throw ValidationException("Nilai pengembalian harus lebih dari 0")
        if (status != InvoiceStatus.PAID && status != InvoiceStatus.REFUNDED) {
            throw ConflictException("Hanya tagihan lunas yang bisa dikembalikan (status sekarang: $status)")
        }
        val next = refundedAmount.add(amount).setScale(2, RoundingMode.HALF_UP)
        if (next > this.amount) {
            throw ConflictException(
                "Pengembalian melebihi nilai tagihan — sisa yang bisa dikembalikan Rp $refundableAmount",
            )
        }
        refundedAmount = next
        if (next.compareTo(this.amount) == 0) status = InvoiceStatus.REFUNDED
    }

    /** Tandai bahwa pengingat mendekati jatuh tempo sudah dikirim (idempoten). */
    fun markDueSoonReminded() {
        dueSoonReminded = true
    }

    /** Hanya tagihan yang masih terbit (ISSUED) yang bisa jatuh tempo. */
    fun markOverdue() {
        if (status != InvoiceStatus.ISSUED) {
            throw ConflictException("Hanya tagihan terbit yang bisa jatuh tempo (status sekarang: $status)")
        }
        status = InvoiceStatus.OVERDUE
    }

    /** Batalkan tagihan; ditolak bila sudah lunas/dikembalikan agar riwayat uangnya tak hilang. */
    fun void() {
        if (status == InvoiceStatus.PAID || status == InvoiceStatus.REFUNDED) {
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

    /**
     * Lekatkan instruksi bayar in-app (mode API) — nomor VA atau string QRIS — tanpa mengubah nilai.
     * Membuang [payUrl] (alur in-app tak me-redirect); menimpa instruksi lama saat pelanggan
     * mengganti instrumen (VA↔QRIS).
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

    companion object {
        /**
         * Terbitkan tagihan dari [baseAmount] (DPP, sebelum pajak). Bila [taxRate] non-null &
         * positif (mis. 0.11), PPN dihitung `baseAmount × taxRate` (skala 2, HALF_UP) dan
         * ditambahkan ke total; bila null/nol, tagihan tanpa PPN (total = dasar) — menjaga
         * perilaku lama tetap utuh.
         */
        @Suppress("LongParameterList")
        fun create(
            tenantId: UUID,
            customerId: UUID,
            subscriptionId: UUID,
            number: String,
            periodStart: LocalDate,
            periodEnd: LocalDate,
            baseAmount: BigDecimal,
            dueDate: LocalDate,
            taxRate: BigDecimal? = null,
            prorated: Boolean = false,
            proratedDays: Int? = null,
        ): Invoice {
            val base = validateAmount(baseAmount)
            val rate = validateTaxRate(taxRate)
            val tax = rate?.let { base.multiply(it).setScale(2, RoundingMode.HALF_UP) } ?: ZERO_MONEY
            return Invoice(
                id = UuidV7.generate(),
                tenantId = tenantId,
                customerId = customerId,
                subscriptionId = subscriptionId,
                number = validateNumber(number),
                periodStart = periodStart,
                periodEnd = periodEnd,
                amount = base.add(tax),
                taxAmount = tax,
                taxRate = rate,
                prorated = prorated,
                proratedDays = validateProratedDays(prorated, proratedDays),
                status = InvoiceStatus.ISSUED,
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
                dueSoonReminded = false,
                refundedAmount = ZERO_MONEY,
            )
        }

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
            taxAmount: BigDecimal,
            taxRate: BigDecimal?,
            prorated: Boolean,
            proratedDays: Int?,
            status: InvoiceStatus,
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
            dueSoonReminded: Boolean,
            refundedAmount: BigDecimal? = null,
        ): Invoice = Invoice(
            id, tenantId, customerId, subscriptionId, number, periodStart, periodEnd, amount,
            taxAmount, taxRate, prorated, proratedDays, status, issuedAt, dueDate, paidAt,
            gatewayProvider, gatewayRef, payUrl, payMethod, vaChannel, vaNumber, vaName,
            vaExpiresAt, qrContent, qrUrl, qrExpiresAt, dueSoonReminded,
            refundedAmount?.setScale(2, RoundingMode.HALF_UP) ?: ZERO_MONEY,
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

        /** Nol uang berskala 2 — nilai PPN default saat tagihan tak dikenai pajak. */
        private val ZERO_MONEY: BigDecimal = BigDecimal.ZERO.setScale(2)

        /**
         * Tarif PPN wajib di rentang [0, 1) — sebuah pecahan (mis. 0.11 untuk 11%), bukan
         * persen. null atau nol → tagihan tanpa PPN (kembalikan null). Dinormalkan ke skala 4.
         */
        private fun validateTaxRate(rate: BigDecimal?): BigDecimal? {
            if (rate == null) return null
            if (rate.signum() < 0) throw ValidationException("Tarif PPN tidak boleh negatif")
            if (rate >= BigDecimal.ONE) throw ValidationException("Tarif PPN harus di bawah 1 (mis. 0.11 untuk 11%)")
            if (rate.signum() == 0) return null
            return rate.setScale(4, RoundingMode.HALF_UP)
        }

        /** Hari prorata wajib >= 1 saat diprorata; diabaikan (null) saat tagihan penuh. */
        private fun validateProratedDays(prorated: Boolean, days: Int?): Int? {
            if (!prorated) return null
            if (days == null || days < 1) throw ValidationException("Hari prorata harus >= 1 saat tagihan diprorata")
            return days
        }
    }
}
