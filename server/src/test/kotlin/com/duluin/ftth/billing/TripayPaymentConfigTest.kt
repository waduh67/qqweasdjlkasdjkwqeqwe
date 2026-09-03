package com.duluin.ftth.billing

import com.duluin.ftth.billing.domain.model.TripayPaymentConfig
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper as Jackson3ObjectMapper

class TripayPaymentConfigTest {

    @Test
    fun `Tripay config redacts credentials from string representation`() {
        val apiKey = "tripay-api-key-for-redaction-test"
        val privateKey = "tripay-private-key-for-redaction-test"
        val config = TripayPaymentConfig(
            merchantCode = "merchant-1",
            apiKey = apiKey,
            privateKey = privateKey,
        )

        assertThat(config.toString()).doesNotContain(apiKey, privateKey)
    }

    @Test
    fun `Tripay config keeps credentials gateway-accessible without data class secret accessors`() {
        val apiKey = "tripay-api-key-for-gateway-access-test"
        val privateKey = "tripay-private-key-for-gateway-access-test"
        val config = TripayPaymentConfig(
            merchantCode = "merchant-1",
            apiKey = apiKey,
            privateKey = privateKey,
        )

        assertThat(config.apiKeyForGateway()).isEqualTo(apiKey)
        assertThat(config.privateKeyForGateway()).isEqualTo(privateKey)
        assertThat(TripayPaymentConfig::class.java.methods.map { it.name })
            .doesNotContain(
                "getApiKey",
                "getPrivateKey",
                "copy",
                "component1",
                "component2",
                "component3",
                "component4",
            )
    }

    @Test
    fun `Tripay config omits credentials from Jackson serialization`() {
        val apiKey = "tripay-api-key-for-serialization-test"
        val privateKey = "tripay-private-key-for-serialization-test"
        val config = TripayPaymentConfig(
            merchantCode = "merchant-1",
            apiKey = apiKey,
            privateKey = privateKey,
            sandbox = false,
        )

        val serializedByJackson2 = ObjectMapper().writeValueAsString(config)
        val serializedByJackson3 = Jackson3ObjectMapper().writeValueAsString(config)

        listOf(serializedByJackson2, serializedByJackson3).forEach { serialized ->
            assertThat(serialized)
                .contains("\"merchantCode\":\"merchant-1\"", "\"sandbox\":false")
                .doesNotContain(apiKey, privateKey)
        }
    }
}
