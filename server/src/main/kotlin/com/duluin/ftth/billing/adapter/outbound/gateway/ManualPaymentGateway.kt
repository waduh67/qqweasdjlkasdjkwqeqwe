package com.duluin.ftth.billing.adapter.outbound.gateway

import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.ChargeResult
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.port.outbound.PaymentGateway
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/**
 * Gateway `MANUAL`: pembayaran di luar band (tunai/transfer) yang diverifikasi
 * operator, plus jalur webhook sederhana untuk sistem eksternal yang menandatangani
 * callback dengan rahasia bersama.
 *
 * `createCharge` tak membuat tautan bayar — pelunasan datang lewat catatan manual
 * atau webhook. `parseCallback` memverifikasi header `X-Billing-Signature` terhadap token
 * verifikasi tenant ([ResolvedGatewayContext.webhookToken], yang untuk fallback MANUAL berisi
 * [BillingProperties.webhookSecret] global); hanya bila cocok body JSON diurai. Callback yang
 * tandanya salah atau bentuknya rusak dikembalikan `null` (ditolak).
 */
@Component
class ManualPaymentGateway(
    private val objectMapper: ObjectMapper,
    private val billingProperties: BillingProperties,
) : PaymentGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider: String = "MANUAL"

    override fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult =
        ChargeResult("MANUAL", null, null)

    override fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext): PaymentSettlement? {
        val expected = ctx.webhookToken ?: billingProperties.webhookSecret
        val signature = callback.headers.entries
            .firstOrNull { it.key.equals(SIGNATURE_HEADER, ignoreCase = true) }
            ?.value
        if (signature.isNullOrBlank() || !constantTimeEquals(signature, expected)) {
            log.warn("Callback MANUAL ditolak — tanda tangan tidak cocok")
            return null
        }
        return runCatching {
            val node = objectMapper.readTree(callback.rawBody)
            val invoiceNumber = node.get("invoiceNumber")?.asString()?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val amountText = node.get("amount")?.asString()?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val paidAt = node.get("paidAt")?.asString()?.takeIf { it.isNotBlank() }
                ?.let { Instant.parse(it) } ?: Instant.now()
            val reference = node.get("reference")?.asString()?.takeIf { it.isNotBlank() }
            PaymentSettlement(
                invoiceNumber = invoiceNumber,
                gatewayRef = reference,
                amount = BigDecimal(amountText),
                paidAt = paidAt,
                provider = "MANUAL",
            )
        }.getOrElse {
            log.warn("Callback MANUAL tidak bisa diurai: {}", it.message)
            null
        }
    }

    /** Bandingkan token tanpa kebocoran waktu (short-circuit `!=` bisa membocorkan panjang/prefix). */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val SIGNATURE_HEADER = "X-Billing-Signature"
    }
}
