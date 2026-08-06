package com.duluin.ftth.billing.adapter.outbound.gateway.pivot

import com.duluin.ftth.common.domain.error.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Kredensial akun Pivot untuk satu panggilan API. Untuk model "business as platform" FTTH ini
 * SELALU akun MASTER platform ([merchantId] = `X-MERCHANT-ID`, [merchantSecret] =
 * `X-MERCHANT-SECRET`); aksi atas nama tenant dijalankan lewat header `x-submerchant-id`
 * (lihat [PivotApiClient.exchange]) — bukan dengan kredensial berbeda.
 */
data class PivotCredentials(
    val merchantId: String,
    val merchantSecret: String,
    val sandbox: Boolean,
)

/**
 * Klien HTTP bersama untuk seluruh permintaan Pivot (charge, sub-account, payout, withdrawal,
 * balance). Menyatukan tiga hal yang dulu tersebar di `PivotPaymentGateway`:
 *
 *  1. **Auth dua-langkah** — `POST /v1/access-token` (header `X-MERCHANT-ID`/`X-MERCHANT-SECRET`)
 *     menukar kredensial jadi Bearer token hidup ~900 dtk. Token di-cache per merchant-id agar
 *     satu ronde penagihan/provisioning tak menukar token berulang.
 *  2. **Base URL** — sandbox (`api-stg`) vs produksi (`api`) dipilih dari [PivotCredentials.sandbox].
 *  3. **Header lintas-fitur** — `x-submerchant-id` (on-behalf-of sub-account) & `X-REQUEST-ID`
 *     (idempotency create payment/payout) disuntik lewat parameter, bukan diulang tiap adapter.
 *
 * Singleton stateless kecuali cache token; [RestClient] dibangun per-panggilan karena base URL
 * bisa beda antar akun (sandbox/prod).
 */
@Component
class PivotApiClient(
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Cache access-token per merchant-id (token hidup ~900 dtk; disegarkan sebelum kedaluwarsa). */
    private val tokenCache = ConcurrentHashMap<String, CachedToken>()

    /**
     * `POST` ber-body JSON ke [path] atas kredensial [creds]. [subMerchantId] non-null → aksi
     * dijalankan atas nama sub-account (header `x-submerchant-id`). [requestId] non-null →
     * idempotency (header `X-REQUEST-ID`). Melempar [ConflictException] bila Pivot menolak.
     */
    fun post(
        path: String,
        body: Any,
        creds: PivotCredentials,
        subMerchantId: String? = null,
        requestId: String? = null,
    ): JsonNode = exchange("POST $path") {
        client(creds).post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { h ->
                h.setBearerAuth(accessToken(creds))
                subMerchantId?.takeIf { it.isNotBlank() }?.let { h.set(SUBMERCHANT_HEADER, it) }
                requestId?.takeIf { it.isNotBlank() }?.let { h.set(REQUEST_ID_HEADER, it) }
            }
            .body(body)
            .retrieve()
            .body(String::class.java)
    }

    /** `PUT` ber-body JSON, semantik sama dengan [post]. */
    fun put(
        path: String,
        body: Any,
        creds: PivotCredentials,
        subMerchantId: String? = null,
    ): JsonNode = exchange("PUT $path") {
        client(creds).put()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { h ->
                h.setBearerAuth(accessToken(creds))
                subMerchantId?.takeIf { it.isNotBlank() }?.let { h.set(SUBMERCHANT_HEADER, it) }
            }
            .body(body)
            .retrieve()
            .body(String::class.java)
    }

    /** `GET` [path] (dengan query string apa adanya bila ada), semantik header sama dengan [post]. */
    fun get(
        path: String,
        creds: PivotCredentials,
        subMerchantId: String? = null,
    ): JsonNode = exchange("GET $path") {
        client(creds).get()
            .uri(path)
            .headers { h ->
                h.setBearerAuth(accessToken(creds))
                subMerchantId?.takeIf { it.isNotBlank() }?.let { h.set(SUBMERCHANT_HEADER, it) }
            }
            .retrieve()
            .body(String::class.java)
    }

    /** Bearer token Pivot: pakai cache per merchant-id, tukar ulang bila kosong/kedaluwarsa. */
    fun accessToken(creds: PivotCredentials): String {
        tokenCache[creds.merchantId]?.takeIf { Instant.now().isBefore(it.expiresAt) }?.let { return it.token }
        val node = exchange("POST /v1/access-token") {
            client(creds).post()
                .uri("/v1/access-token")
                .header("X-MERCHANT-ID", creds.merchantId)
                .header("X-MERCHANT-SECRET", creds.merchantSecret)
                .retrieve()
                .body(String::class.java)
        }
        val token = (node.get("accessToken") ?: node.at("/data/accessToken")).asString().takeIf { it.isNotBlank() }
            ?: throw ConflictException("Respons access token Pivot tak berisi accessToken")
        val ttl = (node.get("expiresIn") ?: node.at("/data/expiresIn")).asLong().takeIf { it > 0 } ?: DEFAULT_TOKEN_TTL
        val expiresAt = Instant.now().plusSeconds((ttl - TOKEN_REFRESH_SKEW).coerceAtLeast(MIN_TOKEN_TTL))
        tokenCache[creds.merchantId] = CachedToken(token, expiresAt)
        return token
    }

    /** Jalankan panggilan, urai body JSON, seragamkan penanganan galat HTTP jadi [ConflictException]. */
    private fun exchange(label: String, call: () -> String?): JsonNode {
        val raw = try {
            call()
        } catch (e: org.springframework.web.client.RestClientResponseException) {
            log.warn("Pivot menolak {} ({}): {}", label, e.statusCode.value(), e.responseBodyAsString.take(ERR_SNIPPET))
            throw ConflictException("Pivot menolak permintaan (${e.statusCode.value()})")
        }
        return raw?.let(objectMapper::readTree) ?: throw ConflictException("Pivot tak mengembalikan body untuk $label")
    }

    /** [RestClient] baru per-panggilan — base URL bisa beda antar akun (sandbox/prod). */
    private fun client(creds: PivotCredentials): RestClient = RestClient.builder()
        .baseUrl(if (creds.sandbox) SANDBOX_BASE_URL else PROD_BASE_URL)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT)
                setReadTimeout(READ_TIMEOUT)
            },
        )
        .build()

    private data class CachedToken(val token: String, val expiresAt: Instant)

    private companion object {
        const val PROD_BASE_URL = "https://api.pivot-payment.com"
        const val SANDBOX_BASE_URL = "https://api-stg.pivot-payment.com"
        const val SUBMERCHANT_HEADER = "x-submerchant-id"
        const val REQUEST_ID_HEADER = "X-REQUEST-ID"
        const val ERR_SNIPPET = 300
        const val DEFAULT_TOKEN_TTL = 900L
        const val TOKEN_REFRESH_SKEW = 60L
        const val MIN_TOKEN_TTL = 30L
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
