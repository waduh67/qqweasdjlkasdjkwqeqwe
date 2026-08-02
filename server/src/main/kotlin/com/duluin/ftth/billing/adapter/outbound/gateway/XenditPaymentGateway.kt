package com.duluin.ftth.billing.adapter.outbound.gateway

import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.ChargeResult
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.port.outbound.PaymentGateway
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.GatewayMode
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

/**
 * Adapter Xendit (BYO & PLATFORM/xenPlatform). Singleton stateless: kredensial datang
 * per-panggilan lewat [ResolvedGatewayContext], jadi [RestClient] dibangun PER-charge dengan
 * secret key tenant (pola [com.duluin.ftth.bng.adapter.outbound.routeros.RouterOsRestAdapter]).
 *
 * Charge = `POST /v2/invoices` (invoice berbayar tautan). Mode PLATFORM menambah header
 * `for-user-id` (sub-account tenant) + `with-fee-rule` (komisi platform). Callback = header
 * `x-callback-token` dibandingkan token tenant; hanya status PAID/SETTLED jadi settlement.
 *
 * IDR di Xendit zero-decimal — amount charge dibulatkan ke bilangan bulat (nilai tagihan di DB
 * tetap scale-2, hanya angka yang dikirim ke Xendit yang dibulatkan).
 */
@Component
class XenditPaymentGateway(
    private val objectMapper: ObjectMapper,
    private val billingProperties: BillingProperties,
) : PaymentGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider: String = "XENDIT"

    override fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult {
        val secret = ctx.secretKey?.takeIf { it.isNotBlank() }
            ?: throw ConflictException("Kredensial Xendit belum lengkap — isi secret key di setelan gateway")
        val body = buildMap<String, Any> {
            put("external_id", request.invoiceNumber)
            put("amount", request.amount.setScale(0, RoundingMode.HALF_UP).toLong())
            put("currency", "IDR")
            put("description", request.description)
            put("customer", mapOf("given_names" to request.customerName))
            request.customerEmail?.takeIf { it.isNotBlank() }?.let { put("payer_email", it) }
            put("invoice_duration", invoiceDurationSeconds())
        }
        return try {
            val node = client(secret).post()
                .uri("/v2/invoices")
                .contentType(MediaType.APPLICATION_JSON)
                .headers { headers ->
                    // Mode PLATFORM: tagih atas nama sub-account tenant + potong komisi platform.
                    if (ctx.mode == GatewayMode.PLATFORM) {
                        ctx.subAccountId?.let { headers.add("for-user-id", it) }
                        ctx.feeRuleId?.let { headers.add("with-fee-rule", it) }
                    }
                }
                .body(body)
                .retrieve()
                .body(String::class.java)
                ?.let(objectMapper::readTree)
                ?: throw ConflictException("Xendit tak mengembalikan body invoice")
            ChargeResult(
                provider = "XENDIT",
                gatewayRef = node.get("id")?.asString()?.takeIf { it.isNotBlank() },
                payUrl = node.get("invoice_url")?.asString()?.takeIf { it.isNotBlank() },
            )
        } catch (e: RestClientResponseException) {
            log.warn("Xendit menolak pembuatan invoice {} ({}): {}", request.invoiceNumber, e.statusCode.value(), e.responseBodyAsString.take(300))
            throw ConflictException("Xendit menolak pembuatan invoice (${e.statusCode.value()})")
        }
    }

    override fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext): PaymentSettlement? {
        val expected = ctx.webhookToken?.takeIf { it.isNotBlank() }
        if (expected == null) {
            log.warn("Callback Xendit ditolak — token verifikasi tenant belum diset")
            return null
        }
        val token = callback.headers.entries
            .firstOrNull { it.key.equals(CALLBACK_TOKEN_HEADER, ignoreCase = true) }
            ?.value
        if (token.isNullOrBlank() || !constantTimeEquals(token, expected)) {
            log.warn("Callback Xendit ditolak — x-callback-token tidak cocok")
            return null
        }
        return runCatching {
            val node = objectMapper.readTree(callback.rawBody)
            val status = node.get("status")?.asString()?.uppercase()
            if (status !in SETTLED_STATUSES) {
                log.info("Callback Xendit diabaikan — status '{}' bukan pelunasan", status)
                return@runCatching null
            }
            val invoiceNumber = node.get("external_id")?.asString()?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val amountText = (node.get("paid_amount") ?: node.get("amount"))?.asString()?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val paidAt = node.get("paid_at")?.asString()?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()
            PaymentSettlement(
                invoiceNumber = invoiceNumber,
                gatewayRef = node.get("id")?.asString()?.takeIf { it.isNotBlank() },
                amount = BigDecimal(amountText),
                paidAt = paidAt,
                provider = "XENDIT",
            )
        }.getOrElse {
            log.warn("Callback Xendit tidak bisa diurai: {}", it.message)
            null
        }
    }

    /** Berapa lama tautan invoice hidup — samakan dengan jendela jatuh tempo tenant. */
    private fun invoiceDurationSeconds(): Long = billingProperties.dueDays.coerceAtLeast(1) * SECONDS_PER_DAY

    /** [RestClient] baru per-charge: secret key beda tiap tenant → tak bisa jadi bean tunggal. */
    private fun client(secretKey: String): RestClient = RestClient.builder()
        .baseUrl(BASE_URL)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT)
                setReadTimeout(READ_TIMEOUT)
            },
        )
        // Xendit basic-auth: secret key sebagai username, password kosong.
        .defaultHeaders { it.setBasicAuth(secretKey, "") }
        .build()

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val BASE_URL = "https://api.xendit.co"
        const val CALLBACK_TOKEN_HEADER = "x-callback-token"
        const val SECONDS_PER_DAY = 86_400L
        val SETTLED_STATUSES = setOf("PAID", "SETTLED")
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
