package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.outbound.PaymentGateway
import com.duluin.ftth.billing.config.BillingProperties
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Component

/**
 * Registri payment gateway: memilih adapter penyedia dari namanya, tanpa peduli
 * penyedia mana yang aktif. Semua bean [PaymentGateway] otomatis terdaftar; pencarian
 * bersifat case-insensitive agar nama dari webhook/konfigurasi luwes.
 *
 * Bagian dari named interface `gateway` — di-expose agar `platformbilling` memilih adapter
 * penyedia untuk menagih langganan SaaS lewat mesin yang sama seperti tagihan pelanggan.
 */
@NamedInterface("gateway")
@Component
class PaymentGatewayRegistry(
    gateways: List<PaymentGateway>,
    private val billingProperties: BillingProperties,
) {
    private val byProvider: Map<String, PaymentGateway> = gateways.associateBy { it.provider.uppercase() }

    fun forProvider(name: String): PaymentGateway? = byProvider[name.uppercase()]

    /** Gateway default (dari `ftth.billing.default-provider`) untuk membuat charge tagihan baru. */
    fun default(): PaymentGateway = forProvider(billingProperties.defaultProvider)
        ?: error("Gateway pembayaran default '${billingProperties.defaultProvider}' tidak tersedia")
}
