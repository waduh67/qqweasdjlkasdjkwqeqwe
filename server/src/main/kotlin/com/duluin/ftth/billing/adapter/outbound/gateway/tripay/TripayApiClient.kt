package com.duluin.ftth.billing.adapter.outbound.gateway.tripay

import com.duluin.ftth.common.domain.error.ConflictException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.MultiValueMap
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration

class TripayCredentials(
    val merchantCode: String,
    apiKey: String,
    privateKey: String,
    val sandbox: Boolean,
) {
    private val apiKeyValue = apiKey
    private val privateKeyValue = privateKey

    fun apiKeyForHttp(): String = apiKeyValue

    fun privateKeyForSignature(): String = privateKeyValue

    override fun toString(): String =
        "TripayCredentials(merchantCode=$merchantCode, apiKeySet=${apiKeyValue.isNotBlank()}, " +
            "privateKeySet=${privateKeyValue.isNotBlank()}, sandbox=$sandbox)"
}

@Component
class TripayApiClient private constructor(
    private val objectMapper: ObjectMapper,
    private val endpointFor: (Boolean) -> String,
    private val connectTimeout: Duration,
    private val readTimeout: Duration,
) {
    @Autowired
    constructor(objectMapper: ObjectMapper) : this(
        objectMapper = objectMapper,
        endpointFor = { sandbox -> if (sandbox) SANDBOX_BASE_URL else PRODUCTION_BASE_URL },
        connectTimeout = CONNECT_TIMEOUT,
        readTimeout = READ_TIMEOUT,
    )

    internal constructor(
        objectMapper: ObjectMapper,
        sandboxBaseUrl: String,
        productionBaseUrl: String,
        connectTimeout: Duration = CONNECT_TIMEOUT,
        readTimeout: Duration = READ_TIMEOUT,
    ) : this(
        objectMapper = objectMapper,
        endpointFor = { sandbox -> if (sandbox) sandboxBaseUrl else productionBaseUrl },
        connectTimeout = connectTimeout,
        readTimeout = readTimeout,
    )

    fun createTransaction(form: MultiValueMap<String, String>, credentials: TripayCredentials): JsonNode {
        val raw = try {
            client(credentials.sandbox).post()
                .uri(CREATE_TRANSACTION_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers { it.setBearerAuth(credentials.apiKeyForHttp()) }
                .body(form)
                .retrieve()
                .body(String::class.java)
        } catch (e: RestClientResponseException) {
            throw ConflictException("Tripay menolak create transaction (${e.statusCode.value()})")
        } catch (e: ResourceAccessException) {
            throw ConflictException("Tripay tidak dapat dihubungi saat membuat transaksi")
        } catch (e: RestClientException) {
            throw ConflictException("Tripay mengembalikan respons create transaction yang tidak dapat diproses")
        }
        if (raw.isNullOrBlank()) throw ConflictException("Tripay tak mengembalikan body create transaction")
        return runCatching { objectMapper.readTree(raw) }
            .getOrElse { throw ConflictException("Respons create transaction Tripay tidak valid") }
    }

    fun parseCallbackPayload(rawBody: String): JsonNode? =
        rawBody.takeIf { it.isNotBlank() }
            ?.let { body -> runCatching { objectMapper.readTree(body) }.getOrNull() }
            ?.takeIf { it.isObject }

    private fun client(sandbox: Boolean): RestClient = RestClient.builder()
        .baseUrl(endpointFor(sandbox))
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(connectTimeout)
                setReadTimeout(readTimeout)
            },
        )
        .build()

    private companion object {
        const val SANDBOX_BASE_URL = "https://tripay.co.id/api-sandbox"
        const val PRODUCTION_BASE_URL = "https://tripay.co.id/api"
        const val CREATE_TRANSACTION_PATH = "/transaction/create"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
