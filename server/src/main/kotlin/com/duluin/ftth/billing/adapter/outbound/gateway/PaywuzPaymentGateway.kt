package com.duluin.ftth.billing.adapter.outbound.gateway

import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.ChargeResult
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.port.outbound.PaymentGateway
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.application.port.outbound.PaywuzMethodDirectory
import com.duluin.ftth.billing.application.port.outbound.PaywuzMethodInfo
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.common.domain.error.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Adapter Paywuz (paywuz.id) — BYO. Singleton stateless: kredensial datang per-panggilan lewat
 * [ResolvedGatewayContext], [RestClient] dibangun per-call.
 *
 * Paywuz hanya butuh **satu** kredensial — API key proyek (`pk_live_…`/`pk_sand_…`) — yang
 * dipakai sekaligus untuk auth (`Authorization: Bearer`) DAN sebagai secret HMAC verifikasi
 * webhook. Karena itu ia dibawa di [ResolvedGatewayContext.secretKey] (lihat `resolveByo`), dan
 * tak ada `webhook_token` terpisah. Lingkungan (sandbox vs live) ditentukan prefiks key, base URL
 * sama.
 *
 * Charge = `POST /v1/transactions` — Paywuz mewajibkan **kode metode** (`paymentMethod`, mis.
 * meta-method `QRIS`/`VA` dari config), berbeda dari halaman hosted Xendit/Pivot. IDR bilangan
 * bulat (amount di DB tetap scale-2, hanya angka yang dikirim dibulatkan). Callback diverifikasi
 * header `X-Paywuz-Signature: sha256=<hex>` = HMAC-SHA256(apiKey, rawBody) dibanding constant-time;
 * hanya status `settlement`/`success` jadi settlement.
 */
@Component
class PaywuzPaymentGateway(
    private val objectMapper: ObjectMapper,
    private val billingProperties: BillingProperties,
) : PaymentGateway, PaywuzMethodDirectory {

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider: String = "PAYWUZ"

    override fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult {
        val apiKey = ctx.secretKey?.takeIf { it.isNotBlank() }
            ?: throw ConflictException("Kredensial Paywuz belum lengkap — isi API key di setelan gateway")
        // Metode per-tenant menang; jatuh ke default global config bila tenant belum memilih.
        val method = ctx.paymentMethod?.takeIf { it.isNotBlank() } ?: billingProperties.paywuz.paymentMethod
        val body = buildMap<String, Any> {
            put("orderId", request.invoiceNumber)
            put("amount", request.amount.setScale(0, RoundingMode.HALF_UP).toLong())
            put("paymentMethod", method)
            put("expiryMinutes", billingProperties.paywuz.expiryMinutes)
            put("metadata", mapOf("invoiceNumber" to request.invoiceNumber))
        }
        return try {
            val node = client(apiKey).post()
                .uri("/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String::class.java)
                ?.let(objectMapper::readTree)
                ?: throw ConflictException("Paywuz tak mengembalikan body transaksi")
            val data = node.get("data") ?: throw ConflictException("Respons Paywuz tanpa objek transaksi")
            ChargeResult(
                provider = "PAYWUZ",
                gatewayRef = data.get("id")?.asString()?.takeIf { it.isNotBlank() },
                payUrl = data.get("paymentUrl")?.asString()?.takeIf { it.isNotBlank() },
            )
        } catch (e: RestClientResponseException) {
            log.warn(
                "Paywuz menolak pembuatan transaksi {} ({}): {}",
                request.invoiceNumber,
                e.statusCode.value(),
                e.responseBodyAsString.take(HTTP_ERR_SNIPPET),
            )
            throw ConflictException("Paywuz menolak pembuatan transaksi (${e.statusCode.value()})")
        }
    }

    override fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext): PaymentSettlement? {
        val apiKey = ctx.secretKey?.takeIf { it.isNotBlank() }
        if (apiKey == null) {
            log.warn("Callback Paywuz ditolak — API key tenant belum diset")
            return null
        }
        val header = callback.headers.entries
            .firstOrNull { it.key.equals(SIGNATURE_HEADER, ignoreCase = true) }
            ?.value
        val expected = SIGNATURE_PREFIX + hmacSha256Hex(apiKey, callback.rawBody)
        if (header.isNullOrBlank() || !constantTimeEquals(header, expected)) {
            log.warn("Callback Paywuz ditolak — X-Paywuz-Signature tidak cocok")
            return null
        }
        return runCatching {
            val node = objectMapper.readTree(callback.rawBody)
            val status = node.get("status")?.asString()?.lowercase()
            if (status !in SETTLED_STATUSES) {
                log.info("Callback Paywuz diabaikan — status '{}' bukan pelunasan", status)
                return@runCatching null
            }
            val orderId = node.get("orderId")?.asString()?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val amountText = node.get("amount")?.asString()?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val paidAt = node.get("timestamp")?.asString()?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()
            PaymentSettlement(
                invoiceNumber = orderId,
                gatewayRef = node.get("id")?.asString()?.takeIf { it.isNotBlank() },
                amount = BigDecimal(amountText),
                paidAt = paidAt,
                provider = "PAYWUZ",
            )
        }.getOrElse {
            log.warn("Callback Paywuz tidak bisa diurai: {}", it.message)
            null
        }
    }

    override fun listMethods(apiKey: String): List<PaywuzMethodInfo> {
        return try {
            val node = client(apiKey).get()
                .uri("/v1/payment-methods")
                .retrieve()
                .body(String::class.java)
                ?.let(objectMapper::readTree)
                ?: return emptyList()
            val data = node.get("data")?.takeIf { it.isArray } ?: return emptyList()
            data.mapNotNull { m ->
                val code = m.get("code")?.asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PaywuzMethodInfo(code = code, name = m.get("name")?.asString()?.takeIf { it.isNotBlank() } ?: code, type = m.get("type")?.asString() ?: "")
            }
        } catch (e: RestClientResponseException) {
            log.warn("Paywuz gagal memuat daftar metode ({}): {}", e.statusCode.value(), e.responseBodyAsString.take(HTTP_ERR_SNIPPET))
            throw ConflictException("Paywuz menolak permintaan daftar metode (${e.statusCode.value()})")
        }
    }

    /** [RestClient] baru per-charge: API key beda tiap tenant → tak bisa jadi bean tunggal. */
    private fun client(apiKey: String): RestClient = RestClient.builder()
        .baseUrl(BASE_URL)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT)
                setReadTimeout(READ_TIMEOUT)
            },
        )
        .defaultHeaders { it.setBearerAuth(apiKey) }
        .build()

    /** HMAC-SHA256(secret, message) → hex huruf-kecil (skema tanda tangan webhook Paywuz). */
    private fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGO))
        return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val BASE_URL = "https://api.paywuz.id"
        const val SIGNATURE_HEADER = "X-Paywuz-Signature"
        const val SIGNATURE_PREFIX = "sha256="
        const val HMAC_ALGO = "HmacSHA256"
        const val HTTP_ERR_SNIPPET = 300
        val SETTLED_STATUSES = setOf("settlement", "success")
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
