package com.duluin.ftth.billing.adapter.outbound.gateway

import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.ChargeResult
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.port.outbound.PaymentGateway
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.common.domain.error.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter Pivot (pivot-payment.com) — BYO. Singleton stateless kecuali cache token: kredensial
 * datang per-panggilan lewat [ResolvedGatewayContext], jadi [RestClient] dibangun per-call.
 *
 * Auth Pivot dua-langkah: `POST /v1/access-token` (header `X-MERCHANT-ID` + `X-MERCHANT-SECRET`)
 * menukar kredensial jadi Bearer token hidup ~900 dtk; token itu dipakai `POST /v2/payments`
 * (payment session mode REDIRECT). Token di-cache per merchant-id agar satu ronde penagihan
 * dengan banyak tagihan tak menukar token berulang. Callback diverifikasi header static
 * `X-API-Key` (Callback API Key per-tenant, BUKAN HMAC) dibanding constant-time; hanya status
 * PAID jadi settlement.
 *
 * IDR zero-decimal — `amount.value` dibulatkan ke bilangan bulat (nilai tagihan di DB tetap
 * scale-2, hanya angka yang dikirim ke Pivot yang dibulatkan), selaras adapter Xendit.
 *
 * Kredensial → kolom: `api_key` = merchant id, `secret_key` = merchant secret, `webhook_token`
 * = Callback API Key. Butuh `ftth.billing.pivot.redirect-base-url` (mode REDIRECT wajib URL balik).
 */
@Component
class PivotPaymentGateway(
    private val objectMapper: ObjectMapper,
    private val billingProperties: BillingProperties,
) : PaymentGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Cache access-token per merchant-id (token hidup ~900 dtk; disegarkan sebelum kedaluwarsa). */
    private val tokenCache = ConcurrentHashMap<String, CachedToken>()

    override val provider: String = "PIVOT"

    override fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult {
        val merchantId = ctx.apiKey?.takeIf { it.isNotBlank() }
            ?: throw ConflictException("Kredensial Pivot belum lengkap — isi Merchant ID di setelan gateway")
        val merchantSecret = ctx.secretKey?.takeIf { it.isNotBlank() }
            ?: throw ConflictException("Kredensial Pivot belum lengkap — isi Merchant Secret di setelan gateway")
        val redirectBase = billingProperties.pivot.redirectBaseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }
            ?: throw ConflictException("Pivot butuh redirect base URL — set FTTH_BILLING_PIVOT_REDIRECT_BASE_URL")

        val amountValue = request.amount.setScale(0, RoundingMode.HALF_UP).toLong()
        val body = buildMap<String, Any> {
            put("clientReferenceId", request.invoiceNumber)
            put("amount", mapOf("value" to amountValue, "currency" to "IDR"))
            put("paymentType", "SINGLE")
            put("mode", "REDIRECT")
            put(
                "redirectUrl",
                mapOf(
                    "successReturnUrl" to "$redirectBase/paid",
                    "failureReturnUrl" to "$redirectBase/failed",
                    "expirationReturnUrl" to "$redirectBase/expired",
                ),
            )
            put(
                "customer",
                buildMap<String, Any> {
                    put("givenName", request.customerName)
                    request.customerEmail?.takeIf { it.isNotBlank() }?.let { put("email", it) }
                },
            )
            // orderInformation WAJIB; satu baris layanan digital. Field ber-enum (category/subCategory/
            // shippingInfo) sengaja diomit — nilainya tak terverifikasi, dan billingInfo hanya wajib
            // untuk Foreign Card AVS (tak relevan pembayaran lokal IDR). Butuh cek sandbox.
            put(
                "orderInformation",
                mapOf(
                    "productDetails" to listOf(
                        mapOf(
                            "type" to "DIGITAL",
                            "name" to request.description.take(MAX_ITEM_NAME),
                            "quantity" to 1,
                            "price" to mapOf("value" to amountValue, "currency" to "IDR"),
                        ),
                    ),
                ),
            )
            put("metadata", mapOf("invoiceNumber" to request.invoiceNumber))
        }
        return try {
            val node = client().post()
                .uri("/v2/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .headers { it.setBearerAuth(accessToken(merchantId, merchantSecret)) }
                .body(body)
                .retrieve()
                .body(String::class.java)
                ?.let(objectMapper::readTree)
                ?: throw ConflictException("Pivot tak mengembalikan body payment session")
            val data = node.get("data")
            ChargeResult(
                provider = "PIVOT",
                gatewayRef = data?.get("id")?.asString()?.takeIf { it.isNotBlank() },
                payUrl = data?.get("paymentUrl")?.asString()?.takeIf { it.isNotBlank() },
            )
        } catch (e: RestClientResponseException) {
            log.warn(
                "Pivot menolak pembuatan payment {} ({}): {}",
                request.invoiceNumber,
                e.statusCode.value(),
                e.responseBodyAsString.take(HTTP_ERR_SNIPPET),
            )
            throw ConflictException("Pivot menolak pembuatan payment (${e.statusCode.value()})")
        }
    }

    override fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext): PaymentSettlement? {
        val expected = ctx.webhookToken?.takeIf { it.isNotBlank() }
        if (expected == null) {
            log.warn("Callback Pivot ditolak — Callback API Key tenant belum diset")
            return null
        }
        val apiKey = callback.headers.entries
            .firstOrNull { it.key.equals(CALLBACK_KEY_HEADER, ignoreCase = true) }
            ?.value
        if (apiKey.isNullOrBlank() || !constantTimeEquals(apiKey, expected)) {
            log.warn("Callback Pivot ditolak — X-API-Key tidak cocok")
            return null
        }
        return runCatching {
            val data = objectMapper.readTree(callback.rawBody).get("data") ?: return@runCatching null
            val status = data.get("status")?.asString()?.uppercase()
            if (status !in SETTLED_STATUSES) {
                log.info("Callback Pivot diabaikan — status '{}' bukan pelunasan", status)
                return@runCatching null
            }
            val invoiceNumber = data.get("clientReferenceId")?.asString()?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val amountText = data.get("amount")?.get("value")?.asString()?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            PaymentSettlement(
                invoiceNumber = invoiceNumber,
                gatewayRef = data.get("id")?.asString()?.takeIf { it.isNotBlank() },
                amount = BigDecimal(amountText),
                paidAt = firstChargePaidAt(data) ?: Instant.now(),
                provider = "PIVOT",
            )
        }.getOrElse {
            log.warn("Callback Pivot tidak bisa diurai: {}", it.message)
            null
        }
    }

    /** Waktu bayar dari `chargeDetails[0].paidAt` bila ada & bisa diurai, else null (pemanggil pakai now). */
    private fun firstChargePaidAt(data: JsonNode): Instant? {
        val charges = data.get("chargeDetails")?.takeIf { it.isArray && !it.isEmpty } ?: return null
        val text = charges.get(0)?.get("paidAt")?.asString()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Instant.parse(text) }.getOrNull()
    }

    /** Bearer token Pivot: pakai cache per merchant-id, tukar ulang bila kosong/kedaluwarsa. */
    private fun accessToken(merchantId: String, merchantSecret: String): String {
        tokenCache[merchantId]?.takeIf { Instant.now().isBefore(it.expiresAt) }?.let { return it.token }
        val node = client().post()
            .uri("/v1/access-token")
            .header("X-MERCHANT-ID", merchantId)
            .header("X-MERCHANT-SECRET", merchantSecret)
            .retrieve()
            .body(String::class.java)
            ?.let(objectMapper::readTree)
            ?: throw ConflictException("Pivot tak mengembalikan access token")
        val token = (node.get("accessToken") ?: node.at("/data/accessToken")).asString().takeIf { it.isNotBlank() }
            ?: throw ConflictException("Respons access token Pivot tak berisi accessToken")
        val ttl = (node.get("expiresIn") ?: node.at("/data/expiresIn")).asLong().takeIf { it > 0 } ?: DEFAULT_TOKEN_TTL
        val expiresAt = Instant.now().plusSeconds((ttl - TOKEN_REFRESH_SKEW).coerceAtLeast(MIN_TOKEN_TTL))
        tokenCache[merchantId] = CachedToken(token, expiresAt)
        return token
    }

    /** [RestClient] baru per-panggilan — kredensial (merchant/token) beda tiap tenant. */
    private fun client(): RestClient = RestClient.builder()
        .baseUrl(if (billingProperties.pivot.sandbox) SANDBOX_BASE_URL else PROD_BASE_URL)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT)
                setReadTimeout(READ_TIMEOUT)
            },
        )
        .build()

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private data class CachedToken(val token: String, val expiresAt: Instant)

    private companion object {
        const val PROD_BASE_URL = "https://api.pivot-payment.com"
        const val SANDBOX_BASE_URL = "https://api-stg.pivot-payment.com"
        const val CALLBACK_KEY_HEADER = "X-API-Key"
        const val MAX_ITEM_NAME = 255
        const val HTTP_ERR_SNIPPET = 300
        const val DEFAULT_TOKEN_TTL = 900L
        const val TOKEN_REFRESH_SKEW = 60L
        const val MIN_TOKEN_TTL = 30L
        val SETTLED_STATUSES = setOf("PAID", "SETTLED", "SUCCESS")
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
