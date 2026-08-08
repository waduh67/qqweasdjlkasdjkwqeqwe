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
    /** Buat sub-account di akun master dari [request] (field wajib lengkap `POST /v1/sub-merchants`). */
    fun create(master: PivotMasterContext, request: SubMerchantCreateRequest): SubMerchantResult

    /** Tarik status terbaru sub-account (`GET /v1/sub-merchants/{uuid}`). */
    fun fetch(master: PivotMasterContext, subMerchantUuid: String): SubMerchantResult

    /**
     * Validasi rekening bank sebelum payout (`POST /v1/inquiry-account`) → `inquiryId` + status
     * kecocokan nama. [accountName] WAJIB dikirim (Pivot mencocokkannya dengan catatan bank), dan
     * panggilan WAJIB on-behalf [subMerchantId]: biaya inquiry dibebankan ke saldo pemanggil, jadi
     * inquiry atas nama master ditolak `balance_insufficient`.
     */
    fun inquiryAccount(
        master: PivotMasterContext,
        subMerchantId: String,
        channelCode: String,
        accountNumber: String,
        accountName: String,
    ): InquiryResult

    /**
     * Undang/assign user admin ke sub-account tenant (`POST /v1/sub-merchants/admin`), on-behalf lewat
     * [subMerchantId] (`x-submerchant-id`). Pivot mengirim email undangan ke [email].
     */
    fun assignUser(master: PivotMasterContext, subMerchantId: String, email: String, name: String)

    /**
     * Kirim ulang undangan ke user yang sudah pernah di-assign (`POST /v1/sub-merchants/users/
     * resend-invitation`), on-behalf lewat [subMerchantId] (`x-submerchant-id`).
     */
    fun resendInvitation(master: PivotMasterContext, subMerchantId: String, email: String)
}

/**
 * Payload create sub-account Pivot — sudah tergabung dari default level-platform (referensi bisnis)
 * + profil spesifik-tenant (identitas/PIC/alamat) + rekening bank opsional. Semua field wajib Pivot
 * sudah non-null di sini; validasi kelengkapan dilakukan di service SEBELUM merakit request ini.
 */
@Suppress("LongParameterList")
data class SubMerchantCreateRequest(
    val type: SubAccountType,
    val shortName: String,
    val name: String,
    val website: String,
    val logo: String,
    val merchantEmail: String,
    val merchantPhone: String,
    val businessCountry: String,
    val businessType: String,
    val businessStructure: String,
    val parentIndustry: String,
    val childIndustry: String,
    val mcc: String,
    val countryOfEntity: String,
    val digitalStatus: String,
    val picName: String,
    val picEmail: String,
    val picPhone: String,
    val address: String,
    val districtId: Int,
    val postCode: String,
    /** Rekening bank tujuan withdrawal (opsional saat create). */
    val bankChannelCode: String?,
    val bankAccountNumber: String?,
)

/** Hasil create/fetch sub-account: uuid + status siklus-hidup & KYC (sudah dipetakan ke enum domain). */
data class SubMerchantResult(
    val subMerchantUuid: String,
    val status: SubAccountStatus,
    val kycStatus: SubAccountKycStatus,
)

/**
 * Hasil validasi rekening: `data.uuid` (dipakai sebagai `inquiryId` saat payout) + `data.inquiryResult`.
 * Pivot TIDAK mengembalikan nama pemilik menurut bank sebagai field tersendiri — kalau namanya tak
 * cocok, nama versi bank ikut di [detail].
 */
data class InquiryResult(
    val inquiryId: String,
    val status: InquiryStatus,
    /** Penjelasan Pivot saat status bukan VALID (mis. "…Bank record: Dummy Simulation"); null bila VALID. */
    val detail: String?,
)

/**
 * Status kecocokan rekening dari Pivot. Hanya [VALID] yang boleh diteruskan jadi payout —
 * [WARNING] berarti nomornya ada tapi namanya beda, dan transfer salah alamat tak bisa ditarik balik.
 */
enum class InquiryStatus { VALID, WARNING, INVALID, PENDING }
