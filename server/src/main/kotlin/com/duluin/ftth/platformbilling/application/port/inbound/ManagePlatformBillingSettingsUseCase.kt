package com.duluin.ftth.platformbilling.application.port.inbound

import java.math.BigDecimal

/**
 * Sisi super-admin: baca & ubah setelan billing global platform (default grace/jatuh-tempo/
 * tanggal-tagih + harga bulanan bawaan). Kredensial pembayaran TIDAK di sini — akun master Pivot
 * dikelola terpisah lewat setelan Pivot platform (`/api/platform/pivot-config`).
 */
interface ManagePlatformBillingSettingsUseCase {
    fun get(): PlatformBillingSettingsView
    fun updateSetting(command: UpdatePlatformSettingsCommand): PlatformBillingSettingsView
}

/** Setelan global penagihan langganan tenant (tanpa kredensial gateway). */
data class PlatformBillingSettingsView(
    val defaultGraceDays: Int,
    val defaultDueDays: Int,
    val defaultBillingDay: Int,
    val defaultMonthlyFee: BigDecimal,
    val currency: String,
)

/** Ubah default global penagihan langganan. */
data class UpdatePlatformSettingsCommand(
    val defaultGraceDays: Int,
    val defaultDueDays: Int,
    val defaultBillingDay: Int,
    val defaultMonthlyFee: BigDecimal,
    val currency: String,
)
