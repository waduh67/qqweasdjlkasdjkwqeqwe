package com.duluin.ftth.billing.application.port.inbound

import com.duluin.ftth.billing.domain.model.PivotFeeType

/**
 * Sisi super-admin platform: baca & ubah setelan akun MASTER Pivot (kredensial, sandbox, fee per
 * transaksi, rekening payout platform). Satu baris global. View TIDAK pernah membocorkan rahasia —
 * hanya penanda boolean apakah kredensial sudah terisi (pola `PlatformGatewayView` lama).
 */
interface ManagePivotMasterConfigUseCase {
    fun get(): PivotMasterConfigView
    fun update(command: UpdatePivotMasterConfigCommand): PivotMasterConfigView
}

/** Ringkasan setelan master Pivot — boolean "sudah diisi" untuk rahasia, nilai apa adanya untuk non-rahasia. */
data class PivotMasterConfigView(
    val enabled: Boolean,
    val sandbox: Boolean,
    val merchantIdSet: Boolean,
    val merchantSecretSet: Boolean,
    val callbackApiKeySet: Boolean,
    val credentialsSet: Boolean,
    val platformFeeMinor: Long,
    val platformFeeType: PivotFeeType,
    val payoutChannelCode: String?,
    val payoutAccountNumber: String?,
    /** Default field wajib create sub-account (non-rahasia → nilai apa adanya). */
    val defaultBusinessType: String?,
    val defaultBusinessStructure: String?,
    val defaultParentIndustry: String?,
    val defaultChildIndustry: String?,
    val defaultMcc: String?,
    val defaultDigitalStatus: String?,
    val defaultBusinessCountry: String?,
    val defaultCountryOfEntity: String?,
    val defaultLogoUrl: String?,
    val defaultWebsite: String?,
    val defaultDistrictId: Int?,
    val defaultPostCode: String?,
)

/** Ubah setelan master. Rahasia (merchantId/secret/callbackKey) null/kosong = biarkan apa adanya. */
@Suppress("LongParameterList")
data class UpdatePivotMasterConfigCommand(
    val enabled: Boolean,
    val sandbox: Boolean,
    val merchantId: String?,
    val merchantSecret: String?,
    val callbackApiKey: String?,
    val platformFeeMinor: Long,
    val platformFeeType: PivotFeeType,
    val payoutChannelCode: String?,
    val payoutAccountNumber: String?,
    /** Default field wajib create sub-account (non-rahasia). */
    val defaultBusinessType: String?,
    val defaultBusinessStructure: String?,
    val defaultParentIndustry: String?,
    val defaultChildIndustry: String?,
    val defaultMcc: String?,
    val defaultDigitalStatus: String?,
    val defaultBusinessCountry: String?,
    val defaultCountryOfEntity: String?,
    val defaultLogoUrl: String?,
    val defaultWebsite: String?,
    val defaultDistrictId: Int?,
    val defaultPostCode: String?,
)
