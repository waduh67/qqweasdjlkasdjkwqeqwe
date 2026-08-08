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
    legalName: String?,
    merchantEmail: String?,
    merchantPhone: String?,
    picName: String?,
    picEmail: String?,
    picPhone: String?,
    address: String?,
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

    /** Nama legal bisnis sub-account (Pivot `name`). Kosong → fallback nama tenant saat provisioning. */
    var legalName: String? = legalName
        private set

    /** Email bisnis sub-account (Pivot `merchantEmail`). */
    var merchantEmail: String? = merchantEmail
        private set

    /** Telepon bisnis sub-account (Pivot `merchantPhone`). */
    var merchantPhone: String? = merchantPhone
        private set

    /** Nama PIC (Pivot `picName`). */
    var picName: String? = picName
        private set

    /** Email PIC (Pivot `picEmail`). */
    var picEmail: String? = picEmail
        private set

    /** Telepon PIC (Pivot `picPhone`). */
    var picPhone: String? = picPhone
        private set

    /** Alamat bisnis sub-account (Pivot `address`). */
    var address: String? = address
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

    /**
     * Profil sudah cukup untuk membuat sub-account: identitas & PIC & alamat + rekening payout terisi.
     * `name` boleh kosong (fallback nama tenant). Rekening bank kini WAJIB — Pivot menolak create
     * sub-account tanpa `bankAccount` (channelCode + accountNumber), jadi digabung ke profil.
     */
    val profileComplete: Boolean
        get() = !merchantEmail.isNullOrBlank() && !merchantPhone.isNullOrBlank() &&
            !picName.isNullOrBlank() && !picEmail.isNullOrBlank() && !picPhone.isNullOrBlank() &&
            !address.isNullOrBlank() &&
            !payoutChannelCode.isNullOrBlank() && !payoutAccountNumber.isNullOrBlank()

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

    /** Simpan profil bisnis sub-account yang diisi tenant (identitas + PIC + alamat). Non-rahasia. */
    @Suppress("LongParameterList")
    fun setProfile(
        legalName: String?,
        merchantEmail: String?,
        merchantPhone: String?,
        picName: String?,
        picEmail: String?,
        picPhone: String?,
        address: String?,
    ) {
        this.legalName = legalName?.trim()?.takeIf { it.isNotEmpty() }
        this.merchantEmail = merchantEmail?.trim()?.takeIf { it.isNotEmpty() }
        this.merchantPhone = merchantPhone?.trim()?.takeIf { it.isNotEmpty() }
        this.picName = picName?.trim()?.takeIf { it.isNotEmpty() }
        this.picEmail = picEmail?.trim()?.takeIf { it.isNotEmpty() }
        this.picPhone = picPhone?.trim()?.takeIf { it.isNotEmpty() }
        this.address = address?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Setel rekening tujuan payout TANPA validasi inquiry — dipakai saat mengisi profil sebelum
     * sub-account dibuat (inquiry `POST /v1/inquiry-account` baru bisa jalan setelah sub-account ada).
     * Rekening ini ikut terkirim sebagai `bankAccount` saat create. Bila rekening berubah, hasil
     * inquiry lama dikosongkan agar divalidasi ulang (payout jadi "belum siap" sampai inquiry sukses).
     */
    fun setPayoutDestination(channelCode: String?, accountNumber: String?, accountName: String?) {
        val cc = channelCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val an = accountNumber?.trim()?.takeIf { it.isNotEmpty() }
        val nm = accountName?.trim()?.takeIf { it.isNotEmpty() }
        if (cc == payoutChannelCode && an == payoutAccountNumber && nm == payoutAccountName) return
        this.payoutChannelCode = cc
        this.payoutAccountNumber = an
        // Nama ikut dikirim ke inquiry (Pivot mencocokkannya dengan catatan bank), jadi disimpan —
        // bukan dikosongkan seperti dulu saat nama dikira datang dari respons Pivot.
        this.payoutAccountName = nm
        this.payoutInquiryId = null
    }

    /**
     * `inquiryId` tersimpan bila rekening yang diminta PERSIS sama dengan yang sudah divalidasi —
     * else null, artinya pemanggil wajib inquiry ulang.
     *
     * Pivot menagih Rp 450 tiap `POST /v1/inquiry-account`, termasuk untuk rekening yang itu-itu
     * juga (hasilnya pun uuid yang sama), dan biayanya dibebankan ke saldo DISBURSEMENT master.
     * Dokumentasi Pivot sendiri menganjurkan menyimpan `inquiryId` dan memakainya ulang selama
     * rekening tujuannya tak berubah. Nama ikut dibandingkan karena Pivot mencocokkannya dengan
     * catatan bank — ganti nama = hasil validasi lama tak berlaku lagi.
     */
    fun cachedInquiryId(channelCode: String, accountNumber: String, accountName: String): String? =
        payoutInquiryId?.takeIf {
            payoutChannelCode == channelCode.trim().uppercase() &&
                payoutAccountNumber == accountNumber.trim() &&
                payoutAccountName == accountName.trim()
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
            legalName = null,
            merchantEmail = null,
            merchantPhone = null,
            picName = null,
            picEmail = null,
            picPhone = null,
            address = null,
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
            legalName: String?,
            merchantEmail: String?,
            merchantPhone: String?,
            picName: String?,
            picEmail: String?,
            picPhone: String?,
            address: String?,
            payoutChannelCode: String?,
            payoutAccountNumber: String?,
            payoutAccountName: String?,
            payoutInquiryId: String?,
        ): TenantPivotAccount = TenantPivotAccount(
            id, tenantId, subMerchantUuid, type, status, kycStatus, shortName,
            legalName, merchantEmail, merchantPhone, picName, picEmail, picPhone, address,
            payoutChannelCode, payoutAccountNumber, payoutAccountName, payoutInquiryId,
        )
    }
}
