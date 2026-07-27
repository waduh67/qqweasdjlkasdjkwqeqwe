package com.duluin.ftth.billing.adapter.outbound.gateway

import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.ChargeResult
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.port.outbound.PaymentGateway
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.config.BillingProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant

/**
 * Gateway `MANUAL`: pembayaran di luar band (tunai/transfer) yang diverifikasi
 * operator, plus jalur webhook sederhana untuk sistem eksternal yang menandatangani
 * callback dengan rahasia bersama.
 *
 * `createCharge` tak membuat tautan bayar — pelunasan datang lewat catatan manual
 * atau webhook. `parseCallback` memverifikasi header `X-Billing-Signature` terhadap
 * [BillingProperties.webhookSecret]; hanya bila cocok body JSON diurai. Callback yang
 * tandanya salah atau bentuknya rusak dikembalikan `null` (ditolak).
 */
@Component
class ManualPaymentGateway(
    private val objectMapper: ObjectMapper,
    private val billingProperties: BillingProperties,
) : PaymentGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider: String = "MANUAL"

    override fun createCharge(request: ChargeRequest): ChargeResult = ChargeResult("MANUAL", null, null)

    override fun parseCallback(callback: GatewayCallback): PaymentSettlement? {
        val signature = callback.headers.entries
            .firstOrNull { it.key.equals(SIGNATURE_HEADER, ignoreCase = true) }
            ?.value
        if (signature.isNullOrBlank() || signature != billingProperties.webhookSecret) {
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

    private companion object {
        const val SIGNATURE_HEADER = "X-Billing-Signature"
    }
}
