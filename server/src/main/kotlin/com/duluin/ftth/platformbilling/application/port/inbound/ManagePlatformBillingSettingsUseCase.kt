package com.duluin.ftth.platformbilling.application.port.inbound

import com.duluin.ftth.platformbilling.domain.model.PlatformPaymentProvider
import java.math.BigDecimal

/**
 * Sisi super-admin: baca & ubah setelan billing global platform (gateway aktif, default
 * grace/jatuh-tempo/tanggal-tagih) + kredensial tiap penyedia. View TIDAK pernah membocorkan
 * rahasia — hanya penanda boolean apakah kredensial sudah terisi.
 */
interface ManagePlatformBillingSettingsUseCase {
    fun get(): PlatformBillingSettingsView
    fun updateSetting(command: UpdatePlatformSettingsCommand): PlatformBillingSettingsView
    fun updateGateway(command: UpdatePlatformGatewayCommand): PlatformBillingSettingsView
}

/** Setelan global + ringkasan tiap penyedia (tanpa rahasia). */
data class PlatformBillingSettingsView(
    val activeProvider: PlatformPaymentProvider,
    val defaultGraceDays: Int,
    val defaultDueDays: Int,
    val defaultBillingDay: Int,
    val defaultMonthlyFee: BigDecimal,
    val currency: String,
    val gateways: List<PlatformGatewayView>,
)

/** Ringkasan kredensial satu penyedia — boolean "sudah diisi", bukan nilai rahasianya. */
data class PlatformGatewayView(
    val provider: PlatformPaymentProvider,
    val enabled: Boolean,
    val apiKeySet: Boolean,
    val secretKeySet: Boolean,
    val webhookTokenSet: Boolean,
    val paymentMethod: String?,
    val credentialsSet: Boolean,
)

/** Ganti gateway aktif + default global. */
data class UpdatePlatformSettingsCommand(
    val activeProvider: PlatformPaymentProvider,
    val defaultGraceDays: Int,
    val defaultDueDays: Int,
    val defaultBillingDay: Int,
    val defaultMonthlyFee: BigDecimal,
    val currency: String,
)

/** Ubah kredensial satu penyedia. Rahasia null/kosong = biarkan apa adanya. */
data class UpdatePlatformGatewayCommand(
    val provider: PlatformPaymentProvider,
    val enabled: Boolean,
    val apiKey: String?,
    val secretKey: String?,
    val webhookToken: String?,
    val paymentMethod: String?,
)
