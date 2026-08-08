package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.outbound.InquiryResult
import com.duluin.ftth.billing.application.port.outbound.InquiryStatus
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException

/**
 * Kebijakan bersama seputar validasi rekening Pivot (`POST /v1/inquiry-account`), dipakai jalur
 * payout beneficiary ([TenantPayoutService]) maupun rekening payout tenant ([TenantPivotAccountService]).
 */

/** Batas panjang `channelInformation.accountName` menurut spec Pivot. */
private const val ACCOUNT_NAME_MAX = 60

/**
 * Nama pemilik rekening yang layak dikirim ke inquiry. Wajib — Pivot menolak 400 `field_required`
 * bila kosong, dengan pesan berlubang "Make sure  value is fulfilled" yang tak menolong siapa pun.
 */
internal fun requireAccountName(raw: String?): String {
    val name = raw?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw ValidationException("Nama pemilik rekening wajib diisi")
    if (name.length > ACCOUNT_NAME_MAX) {
        throw ValidationException("Nama pemilik rekening maksimal $ACCOUNT_NAME_MAX karakter")
    }
    return name
}

/**
 * Loloskan hanya hasil VALID. Nama yang tak cocok berarti uang bisa nyasar ke rekening orang lain
 * dan transfer tak bisa ditarik balik, jadi WARNING ikut ditahan — `detail` Pivot memuat nama versi
 * bank sehingga tenant tinggal membetulkan ejaannya lalu mengulang.
 */
internal fun InquiryResult.requireValid(): InquiryResult = when (status) {
    InquiryStatus.VALID -> this
    InquiryStatus.INVALID -> throw ConflictException(
        "Rekening tak ditemukan — ${detail ?: "periksa bank & nomor rekeningnya"}",
    )
    InquiryStatus.WARNING -> throw ConflictException(
        "Nama pemilik rekening tak cocok catatan bank — ${detail ?: "periksa ejaannya"}",
    )
    InquiryStatus.PENDING -> throw ConflictException(
        "Validasi rekening masih diproses Pivot — coba lagi sebentar lagi",
    )
}
