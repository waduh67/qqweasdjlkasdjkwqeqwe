package com.duluin.ftth.billing.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

/**
 * Alasan pengembalian dana. Nilainya sengaja SAMA PERSIS dengan enum `reason` Pivot
 * (`POST /v1/refunds`) supaya tak perlu tabel terjemahan yang gampang basi: yang dipilih
 * operator itulah yang dikirim ke penyedia dan yang muncul di dashboard mereka.
 */
enum class RefundReason { SUSPECT_FRAUDULENT, DUPLICATE, REQUESTED_BY_CUSTOMER, CANCELLATION, OTHERS }

/**
 * Status siklus-hidup satu pengembalian. [PENDING] baru dicatat lokal; [PROCESSING] sudah diterima
 * penyedia (punya referensi) atau menunggu transfer bank; [SUCCESS]/[FAILED] hasil final —
 * untuk Pivot datang dari callback `REFUND.*`, untuk penyedia MANUAL dari operator yang
 * menyatakan uangnya sudah ditransfer balik.
 *
 * Pivot juga mengenal `CANCELLED` dan `WAITING_BANK_TRANSFER`; keduanya dipetakan ke
 * [FAILED]/[PROCESSING] karena bagi tagihan hanya ada tiga keadaan yang berbeda akibatnya:
 * belum kembali, sudah kembali, batal kembali.
 */
enum class RefundStatus {
    PENDING, PROCESSING, SUCCESS, FAILED;

    /** Uang dianggap masih "dalam perjalanan pulang" — ikut menutup kuota refund tagihan. */
    val open: Boolean get() = this == PENDING || this == PROCESSING
}

/**
 * Satu percobaan mengembalikan uang pelanggan atas sebuah tagihan yang sudah lunas.
 *
 * Dibuat baris tersendiri, bukan [Payment] bernilai negatif: pembayaran bersifat append-only dan
 * menolak nominal negatif, dan yang harus terlihat di sini bukan cuma nominalnya melainkan
 * PERJALANANNYA — diminta, dikirim ke penyedia, lalu berhasil atau gagal. Satu tagihan boleh
 * punya banyak baris (refund sebagian, atau percobaan yang gagal lalu diulang).
 *
 * [amount] berskala 2 seperti [Invoice.amount]/[Payment.amount] (nilai rupiah di DB tetap
 * scale-2; pembulatan ke zero-decimal IDR hanya terjadi di batas adapter penyedia).
 * [gatewayRef] adalah `data.id` refund di penyedia — kunci rekonsiliasi callback.
 */
@Suppress("LongParameterList")
class Refund private constructor(
    val id: UUID,
    val tenantId: UUID,
    val invoiceId: UUID,
    val customerId: UUID,
    /** Pembayaran yang dikembalikan, bila terlacak; null untuk tagihan lama tanpa jejak pembayaran. */
    val paymentId: UUID?,
    val amount: BigDecimal,
    val reason: RefundReason,
    /** Penyedia yang mengembalikan uangnya (`PIVOT`/`MANUAL`) — dibekukan saat diminta. */
    val provider: String,
    val note: String?,
    status: RefundStatus,
    gatewayRef: String?,
    failureReason: String?,
    val requestedAt: Instant,
    completedAt: Instant?,
) {
    var status: RefundStatus = status
        private set

    var gatewayRef: String? = gatewayRef
        private set

    var failureReason: String? = failureReason
        private set

    /** Kapan status final tercapai (berhasil atau gagal); null selama masih berjalan. */
    var completedAt: Instant? = completedAt
        private set

    /** Sudah final — callback susulan atas baris ini tak boleh mengubah apa pun lagi. */
    val settled: Boolean get() = status == RefundStatus.SUCCESS || status == RefundStatus.FAILED

    /**
     * Perintah sudah diterima penyedia: simpan referensinya & angkat ke PROCESSING. Idempoten
     * terhadap baris yang sudah final — callback bisa mendahului respons HTTP-nya sendiri, dan
     * kalau begitu status finalnya yang menang.
     */
    fun markProcessing(ref: String?) {
        ref?.trim()?.takeIf { it.isNotEmpty() }?.let { gatewayRef = it }
        if (status == RefundStatus.PENDING) status = RefundStatus.PROCESSING
    }

    /** Rekonsiliasi: uang benar-benar kembali ke pelanggan. */
    fun markSuccess(at: Instant) {
        if (status == RefundStatus.SUCCESS) return
        status = RefundStatus.SUCCESS
        failureReason = null
        completedAt = at
    }

    /**
     * Rekonsiliasi/error sinkron: pengembalian batal. TIDAK berlaku atas baris yang sudah SUCCESS —
     * callback gagal yang datang terlambat tak boleh menghapus uang yang sudah pindah, karena
     * saldo refund tagihan ikut dihitung dari sini.
     */
    fun markFailed(reason: String?, at: Instant) {
        if (status == RefundStatus.SUCCESS) return
        status = RefundStatus.FAILED
        failureReason = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_REASON)
        completedAt = at
    }

    companion object {
        /** Batas panjang catatan/alasan gagal yang disimpan — kolomnya varchar, bukan teks bebas. */
        const val MAX_NOTE = 200
        private const val MAX_REASON = 200

        fun request(
            tenantId: UUID,
            invoiceId: UUID,
            customerId: UUID,
            paymentId: UUID?,
            amount: BigDecimal,
            reason: RefundReason,
            provider: String,
            note: String?,
            requestedAt: Instant,
        ): Refund = Refund(
            id = UuidV7.generate(),
            tenantId = tenantId,
            invoiceId = invoiceId,
            customerId = customerId,
            paymentId = paymentId,
            amount = validateAmount(amount),
            reason = reason,
            provider = validateProvider(provider),
            note = note?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NOTE),
            status = RefundStatus.PENDING,
            gatewayRef = null,
            failureReason = null,
            requestedAt = requestedAt,
            completedAt = null,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            invoiceId: UUID,
            customerId: UUID,
            paymentId: UUID?,
            amount: BigDecimal,
            reason: RefundReason,
            provider: String,
            note: String?,
            status: RefundStatus,
            gatewayRef: String?,
            failureReason: String?,
            requestedAt: Instant,
            completedAt: Instant?,
        ): Refund = Refund(
            id, tenantId, invoiceId, customerId, paymentId, amount, reason, provider, note,
            status, gatewayRef, failureReason, requestedAt, completedAt,
        )

        private fun validateAmount(amount: BigDecimal): BigDecimal {
            if (amount.signum() <= 0) throw ValidationException("Nilai pengembalian harus lebih dari 0")
            return amount.setScale(2, RoundingMode.HALF_UP)
        }

        private fun validateProvider(provider: String): String {
            val trimmed = provider.trim()
            if (trimmed.isBlank()) throw ValidationException("Provider pengembalian wajib diisi")
            return trimmed
        }
    }
}
