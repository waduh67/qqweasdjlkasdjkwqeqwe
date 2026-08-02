package com.duluin.ftth.billing.adapter.outbound.gateway

import com.duluin.ftth.billing.application.port.outbound.XenditPlatformClient
import com.duluin.ftth.billing.application.port.outbound.XenditSubAccount
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.common.domain.error.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * Adapter klien Xendit tingkat-PLATFORM. Semua panggilan basic-auth secret key MASTER
 * (`ftth.billing.platform.xendit.secret-key`) — bukan kredensial tenant. [RestClient] dibangun
 * per-panggilan (pola [XenditPaymentGateway]/[com.duluin.ftth.bng.adapter.outbound.routeros.RouterOsRestAdapter]).
 *
 * Gate keras: bila mode PLATFORM nonaktif atau secret master kosong, aksi provisioning ditolak
 * ([ConflictException]) — jangan sampai bikin sub-account tanpa kredensial yang benar.
 */
@Component
class XenditPlatformRestAdapter(
    private val objectMapper: ObjectMapper,
    private val billingProperties: BillingProperties,
) : XenditPlatformClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun createManagedSubAccount(email: String, businessName: String): XenditSubAccount {
        val body = mapOf(
            "email" to email,
            "type" to "MANAGED",
            "public_profile" to mapOf("business_name" to businessName),
        )
        return try {
            val node = client().post()
                .uri("/v2/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String::class.java)
                ?.let(objectMapper::readTree)
                ?: throw ConflictException("Xendit tak mengembalikan body akun")
            val userId = node.get("id")?.asString()?.takeIf { it.isNotBlank() }
                ?: throw ConflictException("Respons Xendit tanpa id sub-account")
            // Token callback umumnya TIDAK ikut di respons create — resolve() fallback ke token
            // platform global. Diambil best-effort bila suatu saat Xendit menyertakannya.
            val callbackToken = node.get("callback_token")?.asString()?.takeIf { it.isNotBlank() }
            XenditSubAccount(userId = userId, callbackToken = callbackToken)
        } catch (e: RestClientResponseException) {
            log.warn("Xendit menolak pembuatan sub-account ({}): {}", e.statusCode.value(), e.responseBodyAsString.take(300))
            throw ConflictException("Xendit menolak pembuatan sub-account (${e.statusCode.value()})")
        }
    }

    override fun setInvoiceCallbackUrl(subAccountId: String, callbackUrl: String) {
        try {
            client().post()
                .uri("/callback_urls/invoice")
                .contentType(MediaType.APPLICATION_JSON)
                .header("for-user-id", subAccountId)
                .body(mapOf("url" to callbackUrl))
                .retrieve()
                .toBodilessEntity()
        } catch (e: RestClientResponseException) {
            log.warn("Xendit menolak set callback URL sub-account {} ({}): {}", subAccountId, e.statusCode.value(), e.responseBodyAsString.take(300))
            throw ConflictException("Xendit menolak pemasangan callback URL (${e.statusCode.value()})")
        }
    }

    /** [RestClient] baru per-panggilan, basic-auth secret key MASTER platform. */
    private fun client(): RestClient {
        val platform = billingProperties.platform
        if (!platform.enabled) {
            throw ConflictException("Mode PLATFORM (agregator) nonaktif — nyalakan ftth.billing.platform.enabled dulu")
        }
        val master = platform.xendit.secretKey.trim().takeIf { it.isNotEmpty() }
            ?: throw ConflictException("Secret key master Xendit belum diisi (ftth.billing.platform.xendit.secret-key)")
        return RestClient.builder()
            .baseUrl(BASE_URL)
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(CONNECT_TIMEOUT)
                    setReadTimeout(READ_TIMEOUT)
                },
            )
            // Xendit basic-auth: secret key sebagai username, password kosong.
            .defaultHeaders { it.setBasicAuth(master, "") }
            .build()
    }

    private companion object {
        const val BASE_URL = "https://api.xendit.co"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
