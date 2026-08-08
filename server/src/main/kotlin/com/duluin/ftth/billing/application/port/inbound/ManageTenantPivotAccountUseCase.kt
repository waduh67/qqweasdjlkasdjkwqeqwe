package com.duluin.ftth.billing.application.port.inbound

import com.duluin.ftth.billing.domain.model.PivotFeeType
import com.duluin.ftth.billing.domain.model.SubAccountKycStatus
import com.duluin.ftth.billing.domain.model.SubAccountStatus
import com.duluin.ftth.billing.domain.model.SubAccountType

/**
 * Sisi operator manajemen sub-account Pivot tenant (`/payment-gateway`). Menggantikan form kredensial
 * BYO: tenant tak lagi memasang akun sendiri — platform membuatkan sub-account di akun master saat
 * onboarding. Di sini tenant memantau status, mengajukan upgrade KYC, & menyetel rekening payout.
 */
interface ManageTenantPivotAccountUseCase {
    /** Status sub-account tenant saat ini (bawaan NON_KYC/belum-provisi bila belum ada). */
    fun get(): TenantPivotAccountView

    /** Provisi manual sub-account NON_KYC bila belum ada (idempotent). Untuk tenant lama pra-fitur. */
    fun provision(): TenantPivotAccountView

    /** Tarik status terbaru dari Pivot (`GET /v1/sub-merchants/{uuid}`) & simpan. */
    fun refreshStatus(): TenantPivotAccountView

    /** Ajukan upgrade NON_KYC → KYC (buat sub-account KYC; dokumen dikirim out-of-band ke Pivot). */
    fun requestKyc(): TenantPivotAccountView

    /** Simpan profil bisnis sub-account (identitas + PIC + alamat) — wajib sebelum provisioning. */
    fun saveProfile(command: SaveTenantPivotProfileCommand): TenantPivotAccountView

    /** Setel rekening payout tenant; nomor divalidasi lewat `POST /v1/inquiry-account`. */
    fun setPayoutAccount(command: SetPivotPayoutAccountCommand): TenantPivotAccountView

    /** Undang user admin ke sub-account tenant (`POST /v1/sub-merchants/admin`). */
    fun assignUser(command: AssignPivotUserCommand): TenantPivotAccountView

    /** Kirim ulang undangan user sub-account (`POST /v1/sub-merchants/users/resend-invitation`). */
    fun resendInvitation(command: ResendPivotInvitationCommand): TenantPivotAccountView
}

/**
 * Status sub-account tenant untuk ditampilkan. [masterActive] = platform sudah mengaktifkan akun
 * master Pivot (bila false, provisioning/charge Pivot belum bisa jalan — UI beri tahu tenant).
 * UUID sub-account non-rahasia tapi tak berguna di UI → tak diekspos utuh.
 */
data class TenantPivotAccountView(
    val provisioned: Boolean,
    /**
     * UUID sub-account di Pivot (header `x-submerchant-id`); null bila belum diprovisi. Bukan
     * rahasia — ditampilkan agar bisa dipakai saat rekonsiliasi & panel simulasi pembayaran.
     */
    val subMerchantUuid: String?,
    val type: SubAccountType,
    val status: SubAccountStatus,
    val kycStatus: SubAccountKycStatus,
    val shortName: String?,
    val legalName: String?,
    val merchantEmail: String?,
    val merchantPhone: String?,
    val picName: String?,
    val picEmail: String?,
    val picPhone: String?,
    val address: String?,
    val profileComplete: Boolean,
    val payoutChannelCode: String?,
    val payoutAccountNumber: String?,
    val payoutAccountName: String?,
    val payoutReady: Boolean,
    val masterActive: Boolean,
    /**
     * Biaya yang bakal dipotong tiap payout tenant (Rp bila FIXED, angka persen bila PERCENTAGE).
     * Dibuka ke tenant supaya UI bisa menunjukkan berapa yang benar-benar sampai SEBELUM dikirim —
     * angkanya setelan platform, bukan rahasia. 0 = tak ada potongan.
     */
    val payoutFeeMinor: Long,
    val payoutFeeType: PivotFeeType,
)

/**
 * Profil bisnis sub-account yang diisi tenant. `legalName` opsional (fallback nama tenant).
 * Rekening payout ([channelCode]+[accountNumber]) kini bagian dari profil — Pivot mewajibkan
 * `bankAccount` saat create sub-account, jadi tak lagi langkah terpisah pasca-provisioning.
 */
data class SaveTenantPivotProfileCommand(
    val legalName: String?,
    val merchantEmail: String?,
    val merchantPhone: String?,
    val picName: String?,
    val picEmail: String?,
    val picPhone: String?,
    val address: String?,
    val channelCode: String?,
    val accountNumber: String?,
    /** Nama pemilik rekening — dipakai saat inquiry rekening dijalankan pasca-provisioning. */
    val accountName: String?,
)

/**
 * Setel rekening payout tenant: channel bank + nomor rekening + nama pemilik. Nama WAJIB —
 * Pivot mencocokkannya dengan catatan bank saat `POST /v1/inquiry-account`.
 */
data class SetPivotPayoutAccountCommand(
    val channelCode: String,
    val accountNumber: String,
    val accountName: String,
)

/** Assign user admin ke sub-account tenant: alamat email + nama lengkap (undangan dikirim Pivot). */
data class AssignPivotUserCommand(
    val email: String,
    val name: String,
)

/** Kirim ulang undangan ke user sub-account tenant berdasarkan alamat email. */
data class ResendPivotInvitationCommand(
    val email: String,
)
