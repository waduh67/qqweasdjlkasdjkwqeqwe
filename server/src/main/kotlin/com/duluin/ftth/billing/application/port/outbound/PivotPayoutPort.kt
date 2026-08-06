package com.duluin.ftth.billing.application.port.outbound

import com.duluin.ftth.billing.domain.model.PivotMasterContext

/**
 * Port penyaluran dana Pivot (`/v1/payouts`, `/v1/withdrawals`, `/v1/balances`) di atas akun MASTER
 * platform. Menyembunyikan bentuk JSON Pivot: perintah mengembalikan [PayoutDispatch] (referensi +
 * status awal), pembacaan saldo mengembalikan [BalanceSnapshot].
 *
 * NON_KYC → [payout] (dana di master, disalurkan platform ke rekening tenant memakai `inquiryId`).
 * KYC → [withdraw] on-behalf sub-account (dana di sub-account tenant, ditarik tenant sendiri).
 */
interface PivotPayoutPort {
    /**
     * Salurkan dana dari balance master ke rekening tenant yang sudah divalidasi (`inquiryId`).
     * [requestId] = idempotency `X-REQUEST-ID`.
     */
    fun payout(master: PivotMasterContext, command: PayoutCommand, requestId: String): PayoutDispatch

    /**
     * Tarik saldo sub-account tenant ke rekening tenant (KYC). Dijalankan on-behalf lewat
     * [subMerchantId] (`x-submerchant-id`).
     */
    fun withdraw(
        master: PivotMasterContext,
        subMerchantId: String,
        command: PayoutCommand,
        requestId: String,
    ): PayoutDispatch

    /**
     * Saldo tersedia. [subMerchantId] null = balance master platform; berisi = balance sub-account
     * tenant (on-behalf).
     */
    fun balance(master: PivotMasterContext, subMerchantId: String?): BalanceSnapshot
}

/**
 * Perintah penyaluran. [inquiryId] hasil validasi rekening (`POST /v1/inquiry-account`); untuk
 * withdrawal KYC ke rekening sub-account bisa null bila Pivot memakai rekening terdaftar.
 */
data class PayoutCommand(
    val amountMinor: Long,
    val channelCode: String?,
    val accountNumber: String?,
    val inquiryId: String?,
    val remarks: String?,
)

/** Hasil perintah penyaluran: referensi Pivot untuk rekonsiliasi + apakah sudah final (jarang). */
data class PayoutDispatch(
    val reference: String,
    val settledImmediately: Boolean,
)

/** Cuplikan saldo (minor-unit IDR) untuk ditampilkan; [available] yang boleh disalurkan. */
data class BalanceSnapshot(
    val availableMinor: Long,
    val pendingMinor: Long,
    val currency: String,
)
