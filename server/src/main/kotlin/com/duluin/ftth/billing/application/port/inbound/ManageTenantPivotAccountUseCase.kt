package com.duluin.ftth.billing.application.port.inbound

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

    /** Setel rekening payout tenant; nomor divalidasi lewat `POST /v1/inquiry-account`. */
    fun setPayoutAccount(command: SetPivotPayoutAccountCommand): TenantPivotAccountView
}

/**
 * Status sub-account tenant untuk ditampilkan. [masterActive] = platform sudah mengaktifkan akun
 * master Pivot (bila false, provisioning/charge Pivot belum bisa jalan — UI beri tahu tenant).
 * UUID sub-account non-rahasia tapi tak berguna di UI → tak diekspos utuh.
 */
data class TenantPivotAccountView(
    val provisioned: Boolean,
    val type: SubAccountType,
    val status: SubAccountStatus,
    val kycStatus: SubAccountKycStatus,
    val shortName: String?,
    val payoutChannelCode: String?,
    val payoutAccountNumber: String?,
    val payoutAccountName: String?,
    val payoutReady: Boolean,
    val masterActive: Boolean,
)

/** Setel rekening payout tenant (channel bank + nomor rekening); nama pemilik diisi hasil inquiry. */
data class SetPivotPayoutAccountCommand(
    val channelCode: String,
    val accountNumber: String,
)
