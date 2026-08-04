package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.platformbilling.application.port.outbound.PlatformPaymentGatewayRepository
import com.duluin.ftth.platformbilling.application.port.outbound.PlatformSettingRepository
import com.duluin.ftth.platformbilling.domain.model.PlatformPaymentProvider
import com.duluin.ftth.platformbilling.domain.model.PlatformSetting
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Sumber kebenaran resolusi gateway platform: baca [PlatformSetting] (provider aktif) + baris
 * kredensial penyedia → [ResolvedGatewayContext] terdekripsi siap-pakai. Dipakai charge tagihan
 * (provider aktif) dan verifikasi webhook (provider dari path). Analog `GatewayContextResolver`
 * di module billing, tapi tanpa tenant/RLS.
 */
@Service
@Transactional(readOnly = true)
class PlatformGatewayResolver(
    private val settingRepository: PlatformSettingRepository,
    private val gatewayRepository: PlatformPaymentGatewayRepository,
) {
    /** Setelan global; bawaan bila belum pernah dikonfigurasi. */
    fun setting(): PlatformSetting = settingRepository.find() ?: PlatformSetting.default()

    /** Penyedia yang sedang aktif menagih langganan tenant. */
    fun activeProvider(): PlatformPaymentProvider = setting().activeProvider

    /**
     * Konteks gateway penyedia AKTIF untuk membuat charge. Melempar bila penyedia aktif belum
     * dikonfigurasi/dinyalakan — charge tak boleh diam-diam gagal tanpa sebab jelas.
     */
    fun resolveActive(): ResolvedGatewayContext {
        val provider = activeProvider()
        return resolve(provider)
            ?: throw ConflictException(
                "Gateway aktif '$provider' belum dikonfigurasi — isi & aktifkan kredensialnya di setelan billing platform",
            )
    }

    /** Konteks gateway sebuah penyedia (mis. dari path webhook); null bila mati/tak lengkap. */
    fun resolve(provider: PlatformPaymentProvider): ResolvedGatewayContext? =
        gatewayRepository.findByProvider(provider)?.resolve()

    /** Konteks by nama penyedia (webhook path); null bila nama tak dikenal / mati / tak lengkap. */
    fun resolve(providerName: String): ResolvedGatewayContext? {
        val provider = runCatching { PlatformPaymentProvider.valueOf(providerName.uppercase()) }.getOrNull()
            ?: return null
        return resolve(provider)
    }
}
