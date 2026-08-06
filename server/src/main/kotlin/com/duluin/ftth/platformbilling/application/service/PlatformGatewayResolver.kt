package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.billing.application.service.PivotMasterConfigProvider
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.platformbilling.application.port.outbound.PlatformSettingRepository
import com.duluin.ftth.platformbilling.domain.model.PlatformSetting
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Sumber kebenaran resolusi gateway untuk penagihan langganan SaaS. Model Pivot "business as
 * platform": langganan tenant ditagih LANGSUNG di akun MASTER platform
 * ([com.duluin.ftth.billing.domain.model.PivotMasterConfig], dibaca via [PivotMasterConfigProvider]
 * — named interface `gateway`). TANPA `x-submerchant-id` & TANPA split-routing → 100% dana masuk
 * platform (ini pemasukan platform, bukan tenant).
 *
 * Menggantikan model BYOK lama (baris kredensial per-penyedia). Analog `TenantPaymentGatewayResolver`
 * di module billing, tapi tanpa sub-account/RLS. Dipakai `PlatformInvoiceGenerator` (charge) &
 * `PivotCallbackController` (verifikasi callback pelunasan langganan SaaS).
 */
@Service
@Transactional(readOnly = true)
class PlatformGatewayResolver(
    private val settingRepository: PlatformSettingRepository,
    private val masterConfig: PivotMasterConfigProvider,
) {
    /** Setelan global; bawaan bila belum pernah dikonfigurasi. */
    fun setting(): PlatformSetting = settingRepository.find() ?: PlatformSetting.default()

    /**
     * Konteks gateway master Pivot untuk membuat charge langganan. Melempar bila master Pivot belum
     * dikonfigurasi/dinyalakan di setelan platform — charge tak boleh diam-diam gagal tanpa sebab.
     */
    fun resolveActive(): ResolvedGatewayContext =
        masterContext()
            ?: throw ConflictException(
                "Pivot master belum dikonfigurasi — isi & aktifkan kredensial Pivot di setelan platform",
            )

    /**
     * Konteks by nama penyedia (dari path webhook). Hanya `PIVOT` yang dikenal sekarang; null bila
     * nama lain / master belum aktif → pemanggil menolak callback.
     */
    fun resolve(providerName: String): ResolvedGatewayContext? {
        if (!providerName.equals("PIVOT", ignoreCase = true)) return null
        return masterContext()
    }

    /**
     * Konteks master Pivot untuk penagihan SaaS: 100% ke platform (tanpa sub-account, tanpa fee
     * split). Null bila master nonaktif / kredensial belum lengkap.
     */
    private fun masterContext(): ResolvedGatewayContext? {
        val master = masterConfig.current() ?: return null
        return ResolvedGatewayContext(
            provider = "PIVOT",
            mode = GatewayMode.PLATFORM,
            secretKey = master.merchantSecret,
            webhookToken = master.callbackApiKey,
            subAccountId = null,
            apiKey = master.merchantId,
            sandbox = master.sandbox,
            platformFeeMinor = 0,
            platformFeeType = master.platformFeeType,
        )
    }
}
