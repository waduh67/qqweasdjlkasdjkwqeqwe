package com.duluin.ftth.billing.application.port.outbound

import com.duluin.ftth.billing.domain.model.PivotMasterContext
import com.duluin.ftth.billing.domain.model.SubAccountKycStatus
import com.duluin.ftth.billing.domain.model.SubAccountStatus
import com.duluin.ftth.billing.domain.model.SubAccountType

/**
 * Port keluar operasi sub-merchant Pivot (`/v1/sub-merchants`, `/v1/inquiry-account`). Semua aksi
 * berjalan di akun MASTER platform ([PivotMasterContext]); adapter yang menerjemahkan ke kredensial
 * HTTP + memetakan respons Pivot ke enum domain. Dipakai provisioning & manajemen sub-account tenant.
 */
interface PivotSubMerchantPort {
    /** Buat sub-account [type] untuk tenant; [shortName] jadi transaction descriptor. */
    fun create(master: PivotMasterContext, type: SubAccountType, shortName: String, businessName: String): SubMerchantResult

    /** Tarik status terbaru sub-account (`GET /v1/sub-merchants/{uuid}`). */
    fun fetch(master: PivotMasterContext, subMerchantUuid: String): SubMerchantResult

    /** Validasi rekening bank sebelum payout; hasilkan `inquiryId` + nama pemilik terverifikasi. */
    fun inquiryAccount(master: PivotMasterContext, channelCode: String, accountNumber: String): InquiryResult
}

/** Hasil create/fetch sub-account: uuid + status siklus-hidup & KYC (sudah dipetakan ke enum domain). */
data class SubMerchantResult(
    val subMerchantUuid: String,
    val status: SubAccountStatus,
    val kycStatus: SubAccountKycStatus,
)

/** Hasil validasi rekening: `inquiryId` untuk payout + nama pemilik terverifikasi (bila dikembalikan). */
data class InquiryResult(
    val inquiryId: String,
    val accountName: String?,
)
