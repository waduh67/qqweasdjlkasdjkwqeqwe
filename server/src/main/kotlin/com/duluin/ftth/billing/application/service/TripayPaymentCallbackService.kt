package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.inbound.RecordPaymentUseCase
import com.duluin.ftth.billing.application.port.inbound.TripayCallbackApi
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Owns Tripay callback authentication inside billing so tenant credentials and settlement stay
 * within the billing module. The raw body is authenticated before it is parsed as untrusted JSON.
 */
@Service
class TripayPaymentCallbackService(
    private val tenantApi: TenantApi,
    private val gatewayResolver: TenantPaymentGatewayResolver,
    private val recordPayment: RecordPaymentUseCase,
    private val objectMapper: ObjectMapper,
) : TripayCallbackApi {

    override fun handlePayment(rawBody: ByteArray, callbackSignature: String) {
        val tenantId = authenticateTenant(rawBody, callbackSignature)
        val payload = runCatching { objectMapper.readTree(rawBody) }.getOrNull()
            ?: return
        if (!payload.textValue(STATUS).equals(PAID, ignoreCase = true)) {
            return
        }
        val settlement = payload.toSettlement() ?: return

        TenantContext.runAs(tenantId) { recordPayment.applySettlement(settlement) }
    }

    private fun authenticateTenant(rawBody: ByteArray, callbackSignature: String): UUID {
        val presented = decodeHex(callbackSignature)
            ?: throw ValidationException("Tripay callback signature invalid")
        val matches = tenantApi.findActiveTenantIds().mapNotNull { tenantId ->
            TenantContext.runAs(tenantId) {
                val context = gatewayResolver.resolve()
                if (context.provider.equals(TRIPAY, ignoreCase = true) &&
                    context.mode == GatewayMode.BYO &&
                    !context.secretKey.isNullOrBlank() &&
                    hmacMatches(rawBody, presented, context.secretKey)
                ) {
                    tenantId
                } else {
                    null
                }
            }
        }
        if (matches.size != 1) throw ValidationException("Tripay callback signature invalid")
        return matches.single()
    }

    private fun hmacMatches(rawBody: ByteArray, presented: ByteArray, privateKey: String): Boolean {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(privateKey.toByteArray(StandardCharsets.UTF_8), HMAC_SHA_256))
        return MessageDigest.isEqual(mac.doFinal(rawBody), presented)
    }

    private fun decodeHex(value: String): ByteArray? = runCatching { HexFormat.of().parseHex(value) }.getOrNull()

    private fun JsonNode.toSettlement(): PaymentSettlement? {
        val invoiceNumber = textValue(MERCHANT_REF) ?: return null
        val amount = moneyValue(AMOUNT_RECEIVED, TOTAL_AMOUNT, AMOUNT) ?: return null
        val paidAt = epochSeconds(PAID_AT) ?: return null
        return PaymentSettlement(
            invoiceNumber = invoiceNumber,
            gatewayRef = textValue(REFERENCE),
            amount = amount,
            paidAt = paidAt,
            provider = TRIPAY,
        )
    }

    private fun JsonNode.textValue(field: String): String? =
        get(field)?.takeIf { !it.isNull }?.asString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonNode.moneyValue(vararg fields: String): BigDecimal? {
        for (field in fields) {
            val amount = textValue(field)?.let { runCatching { BigDecimal(it) }.getOrNull() }
            if (amount != null && amount.signum() > 0) return amount
        }
        return null
    }

    private fun JsonNode.epochSeconds(field: String): Instant? =
        get(field)?.takeIf { !it.isNull }?.asLong()?.takeIf { it > 0 }?.let(Instant::ofEpochSecond)

    private companion object {
        const val TRIPAY = "TRIPAY"
        const val PAID = "PAID"
        const val HMAC_SHA_256 = "HmacSHA256"
        const val STATUS = "status"
        const val MERCHANT_REF = "merchant_ref"
        const val REFERENCE = "reference"
        const val AMOUNT = "amount"
        const val AMOUNT_RECEIVED = "amount_received"
        const val TOTAL_AMOUNT = "total_amount"
        const val PAID_AT = "paid_at"
    }
}
