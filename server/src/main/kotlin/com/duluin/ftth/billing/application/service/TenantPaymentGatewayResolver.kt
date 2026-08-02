package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.PlatformGatewayCreds
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import org.springframework.stereotype.Component

/**
 * Menyelesaikan gateway aktif untuk tenant saat ini: baca baris config (via RLS), dekripsi di
 * batas persistence, lalu [com.duluin.ftth.billing.domain.model.TenantPaymentGateway.resolve]
 * menjadi [ResolvedGatewayContext] siap-pakai. Jatuh ke fallback MANUAL (dengan shared secret
 * global) bila tenant belum mengonfigurasi, gateway nonaktif, atau konfigurasinya tak lengkap.
 *
 * Meniru langkah resolusi `NotificationSender` di module notification: satu tempat yang
 * memetakan setelan mentah → bentuk siap-pakai, dipanggil baik saat penerbitan tagihan
 * (charge) maupun saat callback webhook (verifikasi).
 */
@Component
class TenantPaymentGatewayResolver(
    private val repo: TenantPaymentGatewayRepository,
    private val props: BillingProperties,
) {
    fun resolve(): ResolvedGatewayContext =
        repo.find()?.resolve(platformCreds()) ?: manualFallback()

    /** Fallback MANUAL memakai shared secret global — perilaku lama sebelum ada config per-tenant. */
    private fun manualFallback() = ResolvedGatewayContext(
        provider = "MANUAL",
        mode = GatewayMode.BYO,
        secretKey = null,
        webhookToken = props.webhookSecret,
    )

    /** Kredensial master platform, atau null bila platform nonaktif / secret master belum diisi. */
    private fun platformCreds(): PlatformGatewayCreds? {
        val platform = props.platform
        if (!platform.enabled) return null
        val secret = platform.xendit.secretKey.trim().takeIf { it.isNotEmpty() } ?: return null
        return PlatformGatewayCreds(
            secretKey = secret,
            webhookToken = platform.xendit.webhookToken.trim().takeIf { it.isNotEmpty() },
            feeRuleId = platform.xendit.feeRuleId.trim().takeIf { it.isNotEmpty() },
        )
    }
}
