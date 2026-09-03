package com.duluin.ftth.billing.adapter.outbound.gateway

import com.duluin.ftth.billing.PaymentMethodCatalog
import com.duluin.ftth.billing.adapter.outbound.gateway.tripay.TripayApiClient
import com.duluin.ftth.billing.adapter.outbound.gateway.tripay.TripayCredentials
import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.ChargeResult
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.port.outbound.PaymentGateway
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.application.port.outbound.QrInstruction
import com.duluin.ftth.billing.application.port.outbound.VaInstruction
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class TripayPaymentGateway(
    private val apiClient: TripayApiClient,
    private val billingProperties: BillingProperties,
) : PaymentGateway {

    override val provider: String = "TRIPAY"

    override fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult {
        val credentials = credentials(ctx)
        val selection = mapPaymentMethod(request)
        val form = buildChargeForm(request, credentials, selection)
        val response = apiClient.createTransaction(form, credentials)
        if (response.get("success")?.asBoolean() != true) {
            throw ConflictException("Tripay menolak create transaction")
        }
        val data = response.get("data")?.takeIf { it.isObject }
            ?: throw ConflictException("Tripay tak mengembalikan data create transaction")
        return parseCharge(data, selection)
    }

    override fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext): PaymentSettlement? {
        val privateKey = ctx.secretKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val signature = callback.headers.entries
            .firstOrNull { it.key.equals(CALLBACK_SIGNATURE_HEADER, ignoreCase = true) }
            ?.value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val expected = hmacSha256(privateKey, callback.rawBody).toHex()
        if (!MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.US_ASCII),
                signature.lowercase(Locale.ROOT).toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            return null
        }

        val data = apiClient.parseCallbackPayload(callback.rawBody) ?: return null
        if (data.get("status")?.asString() != PAID_STATUS) return null
        val merchantRef = data.get("merchant_ref")?.asString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val reference = data.get("reference")?.asString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val amount = data.get("total_amount")?.asString()?.trim()?.let { rawAmount ->
            runCatching { BigDecimal(rawAmount) }.getOrNull()?.takeIf { it.signum() > 0 }
        } ?: return null
        val paidAt = data.get("paid_at")?.asLong()?.takeIf { it > 0 }?.let { epochSecond ->
            runCatching { Instant.ofEpochSecond(epochSecond) }.getOrNull()
        } ?: return null

        return PaymentSettlement(
            invoiceNumber = merchantRef,
            gatewayRef = reference,
            amount = amount,
            paidAt = paidAt,
            provider = provider,
        )
    }

    internal fun buildChargeForm(
        request: ChargeRequest,
        credentials: TripayCredentials,
        selection: TripayMethodSelection,
    ): MultiValueMap<String, String> {
        val merchantRef = request.invoiceNumber.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Nomor invoice wajib untuk transaksi Tripay")
        val amount = request.amount.setScale(0, RoundingMode.HALF_UP).toBigInteger().toString()
        if (amount.toLongOrNull() == null || amount.toLong() <= 0L) {
            throw ValidationException("Nominal transaksi Tripay harus lebih besar dari nol")
        }
        val customerName = request.customerName.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Nama pembayar wajib untuk transaksi Tripay")
        val customerEmail = request.customerEmail?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Email pembayar wajib untuk transaksi Tripay")
        val itemName = request.description.trim().takeIf { it.isNotEmpty() } ?: merchantRef
        val callbackUrl = billingProperties.tripay.callbackUrl.trim().takeIf { it.isNotEmpty() }
            ?: throw ConflictException("Tripay butuh callback URL — set FTTH_BILLING_TRIPAY_CALLBACK_URL")
        val returnUrl = billingProperties.tripay.returnUrl.trim().takeIf { it.isNotEmpty() }
            ?: throw ConflictException("Tripay butuh return URL — set FTTH_BILLING_TRIPAY_RETURN_URL")

        return LinkedMultiValueMap<String, String>().apply {
            add("method", selection.tripayCode)
            add("merchant_ref", merchantRef)
            add("amount", amount)
            add("customer_name", customerName)
            add("customer_email", customerEmail)
            add("order_items[0][sku]", merchantRef)
            add("order_items[0][name]", itemName)
            add("order_items[0][price]", amount)
            add("order_items[0][quantity]", "1")
            add("callback_url", callbackUrl)
            add("return_url", returnUrl)
            add("signature", merchantSignature(credentials, merchantRef, amount))
        }
    }

    internal fun merchantSignature(credentials: TripayCredentials, merchantRef: String, amount: String): String {
        return hmacSha256(
            credentials.privateKeyForSignature(),
            credentials.merchantCode + merchantRef + amount,
        ).toHex()
    }

    private fun credentials(ctx: ResolvedGatewayContext): TripayCredentials {
        val merchantCode = ctx.merchantCode?.trim()?.takeIf { it.isNotEmpty() }
        val apiKey = ctx.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        val privateKey = ctx.secretKey?.trim()?.takeIf { it.isNotEmpty() }
        if (merchantCode == null || apiKey == null || privateKey == null) {
            throw ConflictException("Konfigurasi Tripay tenant belum lengkap")
        }
        return TripayCredentials(merchantCode, apiKey, privateKey, ctx.sandbox)
    }

    private fun mapPaymentMethod(request: ChargeRequest): TripayMethodSelection {
        val method = request.method?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            ?: throw ConflictException("Metode pembayaran Tripay wajib dipilih")
        return when (method) {
            PaymentMethodCatalog.METHOD_QRIS -> TripayMethodSelection(method, "QRIS", null)
            PaymentMethodCatalog.METHOD_VA -> {
                val channel = request.vaChannel?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                    ?: throw ConflictException("Bank Virtual Account wajib dipilih")
                val tripayCode = VA_METHODS[channel]
                    ?: throw ConflictException("Bank Virtual Account '$channel' belum didukung oleh Tripay")
                TripayMethodSelection(method, tripayCode, channel)
            }
            else -> throw ConflictException("Metode pembayaran '$method' tidak didukung Tripay")
        }
    }

    private fun parseCharge(data: JsonNode, selection: TripayMethodSelection): ChargeResult {
        val gatewayRef = data.get("reference")?.asString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ConflictException("Tripay tak mengembalikan referensi transaksi")
        val expiresAt = data.get("expired_time")?.asString()?.trim()?.takeIf { it.isNotEmpty() }?.let { rawExpiry ->
            val epochSecond = rawExpiry.toLongOrNull()
                ?: throw ConflictException("Tripay mengembalikan waktu kedaluwarsa tidak valid")
            if (epochSecond <= 0) {
                null
            } else {
                runCatching { Instant.ofEpochSecond(epochSecond) }
                    .getOrElse { throw ConflictException("Tripay mengembalikan waktu kedaluwarsa tidak valid") }
            }
        }
        val virtualAccount = if (selection.method == PaymentMethodCatalog.METHOD_VA) {
            val number = data.get("pay_code")?.asString()?.takeIf { it.isNotBlank() }
                ?: throw ConflictException("Tripay tak mengembalikan nomor Virtual Account")
            VaInstruction(
                channel = selection.channel,
                number = number,
                name = data.get("payment_name")?.asString()?.takeIf { it.isNotBlank() },
                expiresAt = expiresAt,
            )
        } else {
            null
        }
        val qr = if (selection.method == PaymentMethodCatalog.METHOD_QRIS) {
            val content = data.get("qr_string")?.asString()?.takeIf { it.isNotBlank() }
                ?: throw ConflictException("Tripay tak mengembalikan string QRIS")
            QrInstruction(
                content = content,
                url = data.get("qr_url")?.asString()?.takeIf { it.isNotBlank() },
                expiresAt = expiresAt,
            )
        } else {
            null
        }
        return ChargeResult(
            provider = provider,
            gatewayRef = gatewayRef,
            payUrl = data.get("checkout_url")?.asString()?.takeIf { it.isNotBlank() }
                ?: data.get("pay_url")?.asString()?.takeIf { it.isNotBlank() },
            status = data.get("status")?.asString()?.takeIf { it.isNotBlank() },
            method = selection.method,
            virtualAccount = virtualAccount,
            qr = qr,
        )
    }

    internal data class TripayMethodSelection(
        val method: String,
        val tripayCode: String,
        val channel: String?,
    )

    private fun hmacSha256(secret: String, payload: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val CALLBACK_SIGNATURE_HEADER = "X-Callback-Signature"
        const val PAID_STATUS = "PAID"
        val VA_METHODS: Map<String, String> = mapOf(
            "BRI" to "BRIVA",
            "BNI" to "BNIVA",
            "MANDIRI" to "MANDIRIVA",
            "BCA" to "BCAVA",
            "BSI" to "BSIVA",
            "CIMB" to "CIMBVA",
            "PERMATA" to "PERMATAVA",
        )
    }
}
