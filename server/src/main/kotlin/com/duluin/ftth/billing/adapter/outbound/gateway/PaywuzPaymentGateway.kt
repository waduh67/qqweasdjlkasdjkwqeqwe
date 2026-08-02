package com.duluin.ftth.billing.adapter.outbound.gateway

import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.ChargeResult
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.port.outbound.PaymentGateway
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Kerangka adapter Paywuz. Provider bisa dipilih & dikonfigurasi tenant (kolom `api_key`/
 * `webhook_token` sudah ada), tapi charge belum jalan — dokumentasi API Paywuz masih di balik
 * login, jadi endpoint & skema tanda tangan belum bisa dipastikan.
 *
 * [createCharge] gagal-cepat dengan pesan jelas (dibungkus per-invoice di [com.duluin.ftth.billing
 * .application.service.InvoiceGenerator] → 1 charge gagal tak membatalkan batch). [parseCallback]
 * menolak callback (`null`) supaya tak ada pelunasan yang lolos tanpa verifikasi. Impl asli tinggal
 * drop-in di sini begitu dokumentasi tersedia.
 */
@Component
class PaywuzPaymentGateway : PaymentGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider: String = "PAYWUZ"

    override fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult =
        throw UnsupportedOperationException("Gateway Paywuz belum didukung — dokumentasi API belum tersedia")

    override fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext): PaymentSettlement? {
        log.warn("Callback Paywuz diabaikan — adapter belum diimplementasikan")
        return null
    }
}
