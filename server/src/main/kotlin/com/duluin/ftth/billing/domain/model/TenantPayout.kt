package com.duluin.ftth.billing.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Jenis penyaluran dana ke rekening tenant:
 *  - [PAYOUT]     dana NON_KYC ada di balance MASTER platform → platform menyalurkan ke rekening
 *                 tenant lewat `POST /v1/payouts` (memakai `payoutInquiryId` tenant). Dipicu
 *                 operator platform (manual/terjadwal).
 *  - [WITHDRAWAL] dana KYC ada di balance SUB-ACCOUNT tenant → tenant menariknya sendiri lewat
 *                 `POST /v1/withdrawals` on-behalf (`x-submerchant-id`).
 */
enum class PayoutKind { PAYOUT, WITHDRAWAL }

/**
 * Status siklus-hidup satu penyaluran. [PENDING] baru dicatat lokal; [PROCESSING] sudah diterima
 * Pivot (punya ref); [SUCCESS]/[FAILED] hasil final dari callback rekonsiliasi (`WITHDRAW.*` /
 * payout webhook), diverifikasi `X-API-Key` master.
 */
enum class PayoutStatus { PENDING, PROCESSING, SUCCESS, FAILED }

/**
 * Riwayat penyaluran dana satu tenant (tenant-scoped + RLS). Satu baris per percobaan payout/
 * withdrawal — jejak audit finansial yang direkonsiliasi callback Pivot. TIDAK menghitung saldo
 * sendiri (Pivot sumber kebenaran balance); baris ini murni mencatat perintah + hasilnya.
 *
 * Nominal disimpan minor-unit IDR (zero-decimal) — konsisten dengan `amount.value` charge Pivot.
 */
class TenantPayout private constructor(
    val id: UUID,
    val tenantId: UUID,
    val kind: PayoutKind,
    val amountMinor: Long,
    /**
     * Biaya platform yang dipotong dari [amountMinor] — dibekukan per baris, bukan dibaca ulang dari
     * setelan: tarifnya bisa berubah dan riwayat harus tetap menunjukkan angka yang berlaku saat itu.
     */
    val feeMinor: Long,
    val channelCode: String?,
    val accountNumber: String?,
    val accountName: String?,
    status: PayoutStatus,
    pivotRef: String?,
    failureReason: String?,
    val createdAt: Instant,
) {
    var status: PayoutStatus = status
        private set

    /** Nominal yang benar-benar mendarat di rekening tujuan — sisa [amountMinor] setelah [feeMinor]. */
    val netAmountMinor: Long get() = amountMinor - feeMinor

    /** Referensi transaksi di Pivot (`data.id`/`referenceId`) — kunci rekonsiliasi callback. */
    var pivotRef: String? = pivotRef
        private set

    var failureReason: String? = failureReason
        private set

    /** Perintah sudah diterima Pivot — simpan ref & tandai PROCESSING (menunggu callback final). */
    fun markProcessing(ref: String) {
        pivotRef = ref.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Referensi payout kosong")
        if (status == PayoutStatus.PENDING) status = PayoutStatus.PROCESSING
    }

    /** Callback rekonsiliasi: penyaluran tuntas. */
    fun markSuccess() {
        status = PayoutStatus.SUCCESS
        failureReason = null
    }

    /** Callback rekonsiliasi / error sinkron: penyaluran gagal. */
    fun markFailed(reason: String?) {
        status = PayoutStatus.FAILED
        failureReason = reason?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** Catat percobaan penyaluran baru (status awal PENDING, belum ada ref Pivot). */
        fun create(
            tenantId: UUID,
            kind: PayoutKind,
            amountMinor: Long,
            feeMinor: Long = 0,
            channelCode: String?,
            accountNumber: String?,
            accountName: String?,
            createdAt: Instant,
        ): TenantPayout {
            if (amountMinor <= 0) throw ValidationException("Nominal penyaluran harus lebih dari 0")
            if (feeMinor < 0) throw ValidationException("Biaya penyaluran tidak boleh negatif")
            if (feeMinor >= amountMinor) {
                throw ValidationException(
                    "Nominal Rp $amountMinor tak menutup biaya payout Rp $feeMinor — minta lebih besar dari biayanya",
                )
            }
            return TenantPayout(
                id = UuidV7.generate(),
                tenantId = tenantId,
                kind = kind,
                amountMinor = amountMinor,
                feeMinor = feeMinor,
                channelCode = channelCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
                accountNumber = accountNumber?.trim()?.takeIf { it.isNotEmpty() },
                accountName = accountName?.trim()?.takeIf { it.isNotEmpty() },
                status = PayoutStatus.PENDING,
                pivotRef = null,
                failureReason = null,
                createdAt = createdAt,
            )
        }

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            kind: PayoutKind,
            amountMinor: Long,
            feeMinor: Long,
            channelCode: String?,
            accountNumber: String?,
            accountName: String?,
            status: PayoutStatus,
            pivotRef: String?,
            failureReason: String?,
            createdAt: Instant,
        ): TenantPayout = TenantPayout(
            id, tenantId, kind, amountMinor, feeMinor, channelCode, accountNumber, accountName,
            status, pivotRef, failureReason, createdAt,
        )
    }
}
