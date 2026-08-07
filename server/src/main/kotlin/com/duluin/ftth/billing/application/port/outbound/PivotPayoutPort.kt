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
     * Salurkan dana dari saldo payout sub-account tenant ke rekening beneficiary (`POST /v1/payouts`),
     * on-behalf lewat [subMerchantId] (`x-submerchant-id`). Body memakai `inquiryId` bila ada, atau
     * `channelInformation`. [requestId] = idempotency `X-REQUEST-ID`.
     */
    fun payout(
        master: PivotMasterContext,
        subMerchantId: String,
        command: PayoutCommand,
        requestId: String,
    ): PayoutDispatch

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
     * Saldo payout tersedia (`GET /v1/payouts/balance?currency=IDR`). [subMerchantId] null = saldo
     * master platform; berisi = saldo sub-account tenant (on-behalf).
     */
    fun balance(master: PivotMasterContext, subMerchantId: String?): BalanceSnapshot
}

/**
 * Perintah penyaluran. [inquiryId] hasil validasi rekening (`POST /v1/inquiry-account`); bila ada,
 * dipakai langsung. Jika null, dipakai jalur `channelCode` + `channelInformation`
 * ([accountNumber]+[accountName]). [referenceId] wajib unik per payout (idempotency bisnis Pivot).
 */
data class PayoutCommand(
    val amountMinor: Long,
    val channelCode: String?,
    val accountNumber: String?,
    val accountName: String?,
    val inquiryId: String?,
    val referenceId: String,
    val description: String?,
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
