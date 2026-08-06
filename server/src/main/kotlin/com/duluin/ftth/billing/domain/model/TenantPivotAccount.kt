package com.duluin.ftth.billing.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/**
 * Tipe akun sub-merchant Pivot milik tenant:
 *  - [NON_KYC] transaksi berjalan ATAS NAMA platform FTTH; dana masuk balance master platform,
 *    payout ke rekening tenant dilakukan platform (manual/terjadwal). Default saat onboarding.
 *  - [KYC]     tenant terverifikasi sendiri ke Pivot; transaksi & saldo ATAS NAMA tenant, tenant
 *    menarik dananya sendiri (withdrawal).
 */
enum class SubAccountType { NON_KYC, KYC }

/**
 * Status siklus-hidup sub-account (dipetakan dari `subAccountStatus` Pivot).
 * [NOT_PROVISIONED] = lokal, sub-account belum pernah dibuat di Pivot.
 */
enum class SubAccountStatus { NOT_PROVISIONED, CREATED, ACTIVE, DEACTIVATED, REJECTED }

/** Status verifikasi KYC (dipetakan dari `subAccountKycStatus` Pivot). */
enum class SubAccountKycStatus { NOT_REQUIRED, WAITING_FOR_DOCUMENT, IN_REVIEW, APPROVED, REJECTED }

/**
 * Sub-account Pivot satu tenant (satu baris per tenant, tenant-scoped + RLS). Menggantikan
 * kredensial gateway BYO: tenant tak lagi memasang akun sendiri — platform membuatkan sub-account
 * di akun master saat onboarding, dan seluruh charge pelanggan tenant dibuat on-behalf-of
 * [subMerchantUuid] ini.
 *
 * Semua field NON-rahasia (uuid sub-account, status, rekening payout bukan kredensial), jadi
 * plaintext. Rekening payout dipakai menyalurkan dana NON_KYC dari balance master ke tenant.
 */
class TenantPivotAccount private constructor(
    val id: UUID,
    val tenantId: UUID,
    subMerchantUuid: String?,
    type: SubAccountType,
    status: SubAccountStatus,
    kycStatus: SubAccountKycStatus,
    shortName: String?,
    payoutChannelCode: String?,
    payoutAccountNumber: String?,
    payoutAccountName: String?,
    payoutInquiryId: String?,
) {
    /** UUID sub-account di Pivot (`x-submerchant-id`). Null = belum diprovisikan. */
    var subMerchantUuid: String? = subMerchantUuid
        private set

    var type: SubAccountType = type
        private set

    var status: SubAccountStatus = status
        private set

    var kycStatus: SubAccountKycStatus = kycStatus
        private set

    /** Transaction descriptor (nama singkat yang muncul di mutasi pelanggan). */
    var shortName: String? = shortName
        private set

    /** Channel bank rekening payout tenant (mis. `BCA`). */
    var payoutChannelCode: String? = payoutChannelCode
        private set

    var payoutAccountNumber: String? = payoutAccountNumber
        private set

    /** Nama pemilik rekening hasil validasi `POST /v1/inquiry-account`. */
    var payoutAccountName: String? = payoutAccountName
        private set

    /** `inquiryId` hasil validasi rekening — dipakai `POST /v1/payouts`. */
    var payoutInquiryId: String? = payoutInquiryId
        private set

    val provisioned: Boolean get() = !subMerchantUuid.isNullOrBlank()
    val payoutReady: Boolean get() = !payoutInquiryId.isNullOrBlank()

    /** Simpan hasil `POST /v1/sub-merchants` (uuid + status awal). */
    fun markProvisioned(subMerchantUuid: String, type: SubAccountType, status: SubAccountStatus, kycStatus: SubAccountKycStatus) {
        this.subMerchantUuid = subMerchantUuid.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("UUID sub-account kosong")
        this.type = type
        this.status = status
        this.kycStatus = kycStatus
    }

    /** Terapkan status dari callback aktivasi / polling `GET /v1/sub-merchants/{uuid}`. */
    fun applyStatus(status: SubAccountStatus, kycStatus: SubAccountKycStatus) {
        this.status = status
        this.kycStatus = kycStatus
    }

    /** Tandai tenant mengajukan upgrade ke KYC (dokumen dikirim out-of-band ke Pivot). */
    fun requestKyc() {
        this.type = SubAccountType.KYC
        this.kycStatus = SubAccountKycStatus.WAITING_FOR_DOCUMENT
    }

    /** Set descriptor transaksi (nama singkat) — non-rahasia. */
    fun setShortName(shortName: String?) {
        this.shortName = shortName?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Simpan rekening payout tenant beserta hasil validasi inquiry (nama + inquiryId). */
    fun setPayoutAccount(channelCode: String, accountNumber: String, accountName: String?, inquiryId: String?) {
        this.payoutChannelCode = channelCode.trim().uppercase().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Channel bank kosong")
        this.payoutAccountNumber = accountNumber.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Nomor rekening kosong")
        this.payoutAccountName = accountName?.trim()?.takeIf { it.isNotEmpty() }
        this.payoutInquiryId = inquiryId?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** Baris bawaan tenant yang belum diprovisikan — NON_KYC, belum ada di Pivot. */
        fun defaultFor(tenantId: UUID): TenantPivotAccount = TenantPivotAccount(
            id = UuidV7.generate(),
            tenantId = tenantId,
            subMerchantUuid = null,
            type = SubAccountType.NON_KYC,
            status = SubAccountStatus.NOT_PROVISIONED,
            kycStatus = SubAccountKycStatus.NOT_REQUIRED,
            shortName = null,
            payoutChannelCode = null,
            payoutAccountNumber = null,
            payoutAccountName = null,
            payoutInquiryId = null,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            subMerchantUuid: String?,
            type: SubAccountType,
            status: SubAccountStatus,
            kycStatus: SubAccountKycStatus,
            shortName: String?,
            payoutChannelCode: String?,
            payoutAccountNumber: String?,
            payoutAccountName: String?,
            payoutInquiryId: String?,
        ): TenantPivotAccount = TenantPivotAccount(
            id, tenantId, subMerchantUuid, type, status, kycStatus, shortName,
            payoutChannelCode, payoutAccountNumber, payoutAccountName, payoutInquiryId,
        )
    }
}
