package com.duluin.ftth.platformbilling

import com.duluin.ftth.billing.application.port.inbound.TripayCallbackApi
import com.duluin.ftth.common.infrastructure.config.CorsProperties
import com.duluin.ftth.common.infrastructure.config.ObservabilityProperties
import com.duluin.ftth.common.infrastructure.config.SecurityConfig
import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.common.infrastructure.security.JwtAuthenticationConverter
import com.duluin.ftth.platformbilling.adapter.inbound.web.TripayCallbackController
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(controllers = [TripayCallbackController::class])
@Import(SecurityConfig::class, JwtAuthenticationConverter::class, TripayCallbackSecurityTest.CallbackBeans::class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration::class)
@EnableConfigurationProperties(SecurityProperties::class, CorsProperties::class, ObservabilityProperties::class)
@TestPropertySource(
    properties = [
        "ftth.security.jwt-secret=callback-security-test-secret-1234567890",
        "ftth.security.encryption-secret=callback-security-encryption-secret-123456",
    ],
)
class TripayCallbackSecurityTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var callbacks: RecordingCallbacks

    @Test
    fun `unauthenticated POST payment callback is public and reaches the raw callback boundary`() {
        val rawBody = "{\"tenant_id\":\"untrusted\"}"

        mockMvc.perform(
            post(PAYMENT_CALLBACK_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(CALLBACK_SIGNATURE_HEADER, "fixture-signature")
                .content(rawBody),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))

        check(callbacks.rawBodies.size == 1)
        check(callbacks.rawBodies.single().contentEquals(rawBody.toByteArray()))
        check(callbacks.signatures == listOf("fixture-signature"))
    }

    @Test
    fun `only the POST payment callback route is public`() {
        mockMvc.perform(get(PAYMENT_CALLBACK_PATH))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(post("/api/platform/tripay/callbacks/other"))
            .andExpect(status().isUnauthorized)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class CallbackBeans {
        @Bean
        fun tripayCallbackApi(): RecordingCallbacks = RecordingCallbacks()
    }

    class RecordingCallbacks : TripayCallbackApi {
        val rawBodies = mutableListOf<ByteArray>()
        val signatures = mutableListOf<String>()

        override fun handlePayment(rawBody: ByteArray, callbackSignature: String) {
            rawBodies += rawBody
            signatures += callbackSignature
        }
    }

    private companion object {
        const val PAYMENT_CALLBACK_PATH = "/api/platform/tripay/callbacks/payment"
        const val CALLBACK_SIGNATURE_HEADER = "X-Callback-Signature"
    }
}
