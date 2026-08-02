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
 * Kerangka adapter Pivot. Sama seperti [PaywuzPaymentGateway]: bisa dipilih & dikonfigurasi
 * (kolom `api_key`/`webhook_token` reuse), tapi charge belum jalan karena dokumentasi API-nya
 * belum tersedia. [createCharge] gagal-cepat, [parseCallback] menolak callback (`null`). Impl
 * asli tinggal drop-in begitu dokumentasi diberikan.
 */
@Component
class PivotPaymentGateway : PaymentGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider: String = "PIVOT"

    override fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult =
        throw UnsupportedOperationException("Gateway Pivot belum didukung — dokumentasi API belum tersedia")

    override fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext): PaymentSettlement? {
        log.warn("Callback Pivot diabaikan — adapter belum diimplementasikan")
        return null
    }
}
