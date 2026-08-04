package com.duluin.ftth.billing.adapter.outbound.gateway

import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.ChargeResult
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.port.outbound.PaymentGateway
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
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
 * Adapter Midtrans (Snap API). Singleton stateless: kredensial datang per-panggilan lewat
 * [ResolvedGatewayContext], [RestClient] dibangun per-call.
 *
 * Midtrans butuh **satu** kredensial — Server Key — yang dipakai untuk Basic-auth Snap
 * (`base64(serverKey + ":")`) SEKALIGUS sebagai secret verifikasi signature webhook. Karena itu
 * ia dibawa di [ResolvedGatewayContext.secretKey]. Lingkungan (sandbox vs production) ditentukan
 * prefiks key (`SB-…` = sandbox), base URL berbeda.
 *
 * Charge = `POST /snap/v1/transactions` → mengembalikan `token` + `redirect_url` (halaman Snap
 * hosted); [ChargeResult.gatewayRef] memakai `order_id` (= nomor tagihan) karena Snap tak
 * mengembalikan transaction_id di respons pembuatan. Webhook diverifikasi
 * `signature_key == SHA512(order_id + status_code + gross_amount + serverKey)` dibanding
 * constant-time; hanya `settlement` atau `capture` dengan `fraud_status=accept` jadi settlement.
 */
@Component
class MidtransPaymentGateway(
    private val objectMapper: ObjectMapper,
) : PaymentGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider: String = "MIDTRANS"

    override fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult {
        val serverKey = ctx.secretKey?.takeIf { it.isNotBlank() }
            ?: throw ConflictException("Kredensial Midtrans belum lengkap — isi Server Key di setelan gateway")
        val grossAmount = request.amount.setScale(0, RoundingMode.HALF_UP).toLong()
        val body = mapOf(
            "transaction_details" to mapOf(
                "order_id" to request.invoiceNumber,
                "gross_amount" to grossAmount,
            ),
            "item_details" to listOf(
                mapOf(
                    "id" to request.invoiceNumber,
                    "price" to grossAmount,
                    "quantity" to 1,
                    "name" to request.description.take(ITEM_NAME_MAX),
                ),
            ),
            "customer_details" to buildMap {
                put("first_name", request.customerName.take(CUSTOMER_NAME_MAX))
                request.customerEmail?.takeIf { it.isNotBlank() }?.let { put("email", it) }
            },
        )
        return try {
            val node = client(serverKey).post()
                .uri("/snap/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String::class.java)
                ?.let(objectMapper::readTree)
                ?: throw ConflictException("Midtrans tak mengembalikan body transaksi")
            ChargeResult(
                provider = "MIDTRANS",
                // Snap tak mengembalikan transaction_id saat pembuatan → pakai order_id sebagai ref.
                gatewayRef = request.invoiceNumber,
                payUrl = node.get("redirect_url")?.asString()?.takeIf { it.isNotBlank() },
            )
        } catch (e: RestClientResponseException) {
            log.warn(
                "Midtrans menolak pembuatan transaksi {} ({}): {}",
                request.invoiceNumber,
                e.statusCode.value(),
                e.responseBodyAsString.take(HTTP_ERR_SNIPPET),
            )
            throw ConflictException("Midtrans menolak pembuatan transaksi (${e.statusCode.value()})")
        }
    }

    override fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext): PaymentSettlement? {
        val serverKey = ctx.secretKey?.takeIf { it.isNotBlank() }
        if (serverKey == null) {
            log.warn("Callback Midtrans ditolak — Server Key belum diset")
            return null
        }
        return runCatching {
            val node = objectMapper.readTree(callback.rawBody)
            val orderId = node.get("order_id")?.asString()?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val statusCode = node.get("status_code")?.asString()?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val grossAmount = node.get("gross_amount")?.asString()?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val signature = node.get("signature_key")?.asString()?.takeIf { it.isNotBlank() }

            val expected = sha512Hex(orderId + statusCode + grossAmount + serverKey)
            if (signature == null || !constantTimeEquals(signature, expected)) {
                log.warn("Callback Midtrans ditolak — signature_key tidak cocok untuk order {}", orderId)
                return@runCatching null
            }

            val transactionStatus = node.get("transaction_status")?.asString()?.lowercase()
            val fraudStatus = node.get("fraud_status")?.asString()?.lowercase()
            val settled = when (transactionStatus) {
                "settlement" -> true
                // `capture` (kartu) baru dianggap lunas bila lolos fraud review.
                "capture" -> fraudStatus == null || fraudStatus == "accept"
                else -> false
            }
            if (!settled) {
                log.info("Callback Midtrans diabaikan — status '{}' bukan pelunasan", transactionStatus)
                return@runCatching null
            }
            val paidAt = node.get("settlement_time")?.asString()?.takeIf { it.isNotBlank() }
                ?.let { runCatching { parseMidtransTime(it) }.getOrNull() } ?: Instant.now()
            PaymentSettlement(
                invoiceNumber = orderId,
                gatewayRef = node.get("transaction_id")?.asString()?.takeIf { it.isNotBlank() } ?: orderId,
                amount = BigDecimal(grossAmount),
                paidAt = paidAt,
                provider = "MIDTRANS",
            )
        }.getOrElse {
            log.warn("Callback Midtrans tidak bisa diurai: {}", it.message)
            null
        }
    }

    /** [RestClient] baru per-charge: Server Key menentukan environment (sandbox/prod) & auth. */
    private fun client(serverKey: String): RestClient = RestClient.builder()
        .baseUrl(if (isSandbox(serverKey)) SANDBOX_BASE_URL else PRODUCTION_BASE_URL)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT)
                setReadTimeout(READ_TIMEOUT)
            },
        )
        // Basic auth: username = Server Key, password kosong.
        .defaultHeaders {
            it.setBasicAuth(serverKey, "")
            it.accept = listOf(MediaType.APPLICATION_JSON)
        }
        .build()

    /** Server key sandbox berprefiks `SB-` (mis. `SB-Mid-server-…`). */
    private fun isSandbox(serverKey: String): Boolean = serverKey.startsWith("SB-", ignoreCase = true)

    /** SHA-512(message) → hex huruf-kecil (skema signature webhook Midtrans). */
    private fun sha512Hex(message: String): String =
        MessageDigest.getInstance(SIGNATURE_ALGO)
            .digest(message.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    /** `settlement_time` Midtrans berformat `yyyy-MM-dd HH:mm:ss` waktu WIB (UTC+7). */
    private fun parseMidtransTime(raw: String): Instant =
        java.time.LocalDateTime
            .parse(raw.trim().replace(' ', 'T'))
            .atOffset(java.time.ZoneOffset.ofHours(7))
            .toInstant()

    private companion object {
        const val SANDBOX_BASE_URL = "https://app.sandbox.midtrans.com"
        const val PRODUCTION_BASE_URL = "https://app.midtrans.com"
        const val SIGNATURE_ALGO = "SHA-512"
        const val HTTP_ERR_SNIPPET = 300
        const val ITEM_NAME_MAX = 50
        const val CUSTOMER_NAME_MAX = 60
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
