package com.duluin.ftth.billing.application.port.inbound

import com.duluin.ftth.billing.domain.model.PayoutKind
import com.duluin.ftth.billing.domain.model.PayoutStatus
import java.time.Instant

/**
 * Penyaluran dana tenant. Dua jalur (lihat [PayoutKind]):
 *  - PAYOUT (NON_KYC) — operator platform menyalurkan dana dari balance master ke rekening tenant.
 *  - WITHDRAWAL (KYC) — tenant menarik saldo sub-account-nya sendiri.
 * Nominal eksplisit (minor-unit IDR) — TIDAK ada akrual otomatis; saldo dibaca dari Pivot ([balance]).
 */
interface ManageTenantPayoutUseCase {
    /** Riwayat penyaluran tenant aktif, terbaru-dahulu. */
    fun history(): List<TenantPayoutView>

    /** Saldo pembayaran tenant — hasil tagihan pelanggan (master bila sub-account belum terprovisi). */
    fun balance(): PivotBalanceView

    /** Salurkan dana ke rekening beneficiary bebas (validasi inquiry + wajib cek saldo dulu). */
    fun dispatchPayout(command: DispatchPayoutCommand): TenantPayoutView

    /** Tarik saldo sub-account KYC tenant ke rekening payout tersimpan (aksi tenant sendiri). */
    fun withdraw(command: WithdrawCommand): TenantPayoutView
}

/** Rekonsiliasi hasil penyaluran dari callback Pivot (payout webhook / `WITHDRAW.*`). */
interface ReconcilePayoutUseCase {
    /** Perbarui status baris via ref Pivot. Idempotent — callback ganda aman. */
    fun reconcile(reference: String, success: Boolean, reason: String?)
}

/**
 * Perintah payout ke rekening beneficiary bebas: bank ([channelCode]) + [accountNumber] + nominal
 * (rupiah utuh). Nama pemilik divalidasi server via inquiry; saldo dicek wajib sebelum create.
 */
data class DispatchPayoutCommand(
    val channelCode: String,
    val accountNumber: String,
    /** Nama pemilik rekening — wajib, dicocokkan Pivot dengan catatan bank saat inquiry. */
    val accountName: String,
    val amountMinor: Long,
    val description: String?,
)

/** Perintah penarikan saldo sub-account KYC tenant ke rekening payout tersimpan. */
data class WithdrawCommand(
    val amountMinor: Long,
    val description: String?,
)

/**
 * Satu baris riwayat penyaluran untuk ditampilkan.
 *
 * [amountMinor] = nominal yang diminta tenant; [feeMinor] = biaya payout yang dipotong, DIBEKUKAN
 * per baris (tarifnya setelan yang bisa berubah, riwayat harus tetap menunjukkan angka saat itu);
 * [netAmountMinor] = yang benar-benar mendarat di rekening tujuan.
 */
data class TenantPayoutView(
    val id: String,
    val kind: PayoutKind,
    val amountMinor: Long,
    val feeMinor: Long,
    val netAmountMinor: Long,
    val channelCode: String?,
    val accountNumber: String?,
    val accountName: String?,
    val status: PayoutStatus,
    val pivotRef: String?,
    val failureReason: String?,
    val createdAt: Instant,
)

/**
 * Cuplikan saldo PEMBAYARAN tenant (rupiah utuh) — dana hasil tagihan pelanggan, bukan saldo payout.
 * [subAccount] = benar bila saldo dibaca on-behalf sub-account tenant (bukan master platform).
 */
data class PivotBalanceView(
    val availableMinor: Long,
    val currency: String,
    val subAccount: Boolean,
)
