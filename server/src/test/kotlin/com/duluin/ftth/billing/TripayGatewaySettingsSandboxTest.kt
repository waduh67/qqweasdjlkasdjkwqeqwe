package com.duluin.ftth.billing

import com.duluin.ftth.billing.adapter.inbound.web.PaymentGatewaySettingsController
import com.duluin.ftth.billing.adapter.inbound.web.TripaySandboxTestRequest
import com.duluin.ftth.billing.adapter.outbound.gateway.TripayPaymentGateway
import com.duluin.ftth.billing.adapter.outbound.gateway.tripay.TripayApiClient
import com.duluin.ftth.billing.application.port.inbound.ManagePaymentGatewaySettingsUseCase
import com.duluin.ftth.billing.application.port.inbound.TestTripaySandboxCommand
import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.application.service.TripaySandboxTestService
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.config.TripayProperties
import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.billing.domain.model.TripayPaymentConfig
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.web.GlobalExceptionHandler
import com.duluin.ftth.common.tenant.TenantContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PostMapping
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TripayGatewaySettingsSandboxTest {

    private val objectMapper = JsonMapper.builder().build()
    private val tenantId = UuidV7.generate()

    @AfterEach
    fun clearTenantContext() = TenantContext.clear()

    @Test
    fun `draft credentials create unique small transactions through the sandbox API and propagate checkout URL`() {
        WireTripay(successResponse()).use { wire ->
            val repository = NoSaveRepository(storedSettings())
            val service = service(repository, wire)

            val first = service.testTripay(
                TestTripaySandboxCommand(
                    merchantCode = "DRAFT-MERCHANT",
                    apiKey = " draft-api-key ",
                    privateKey = " draft-private-key ",
                ),
            )
            service.testTripay(
                TestTripaySandboxCommand(
                    merchantCode = "DRAFT-MERCHANT",
                    apiKey = "draft-api-key",
                    privateKey = "draft-private-key",
                ),
            )

            assertThat(first.paymentUrl).isEqualTo(PAYMENT_URL)
            assertThat(wire.requests).hasSize(2)
            assertThat(wire.requests.map { it.path })
                .containsOnly("/api-sandbox/transaction/create")
            assertThat(wire.requests.map { it.authorization })
                .containsOnly("Bearer draft-api-key")
            assertThat(wire.requests.map { it.form.getValue("amount") }).containsOnly("1000")
            assertThat(wire.requests.map { it.form.getValue("method") }).containsOnly("QRIS")
            assertThat(wire.requests.map { it.form.getValue("merchant_ref") })
                .allMatch { it.startsWith("TST-") }
                .doesNotHaveDuplicates()
            wire.requests.forEach { request ->
                val merchantRef = request.form.getValue("merchant_ref")
                assertThat(request.form.getValue("signature"))
                    .isEqualTo(sign("draft-private-key", "DRAFT-MERCHANT${merchantRef}1000"))
            }
            assertThat(repository.saveCalls).isZero()
        }
    }

    @Test
    fun `blank or missing draft secrets fall back to stored credentials but stored production mode cannot escape sandbox`() {
        WireTripay(successResponse()).use { wire ->
            val repository = NoSaveRepository(
                storedSettings(
                    apiKey = "stored-api-key",
                    privateKey = "stored-private-key",
                    sandbox = false,
                ),
            )

            service(repository, wire).testTripay(
                TestTripaySandboxCommand(
                    merchantCode = "CURRENT-MERCHANT",
                    apiKey = "  \t ",
                    privateKey = null,
                ),
            )

            val request = wire.request
            assertThat(request.path).isEqualTo("/api-sandbox/transaction/create")
            assertThat(request.authorization).isEqualTo("Bearer stored-api-key")
            assertThat(request.form.getValue("signature")).isEqualTo(
                sign(
                    "stored-private-key",
                    "CURRENT-MERCHANT${request.form.getValue("merchant_ref")}1000",
                ),
            )
            assertThat(repository.saveCalls).isZero()
        }
    }

    @Test
    fun `missing merchant or effective secrets fail before HTTP and never save`() {
        WireTripay(successResponse()).use { wire ->
            val repository = NoSaveRepository()
            val service = service(repository, wire)

            assertThatThrownBy {
                service.testTripay(TestTripaySandboxCommand(merchantCode = "  ", apiKey = "api", privateKey = "private"))
            }.isInstanceOf(ValidationException::class.java)

            assertThatThrownBy {
                service.testTripay(TestTripaySandboxCommand(merchantCode = "MERCHANT", apiKey = " ", privateKey = null))
            }.isInstanceOf(ValidationException::class.java)

            assertThat(wire.requests).isEmpty()
            assertThat(repository.saveCalls).isZero()
        }
    }

    @Test
    fun `Tripay success without a usable payment URL is not reported as a successful test`() {
        WireTripay(successResponse(paymentUrl = null)).use { wire ->
            val repository = NoSaveRepository()

            assertThatThrownBy {
                service(repository, wire).testTripay(
                    TestTripaySandboxCommand(
                        merchantCode = "MERCHANT",
                        apiKey = "api-key",
                        privateKey = "private-key",
                    ),
                )
            }.isInstanceOf(ConflictException::class.java)
                .hasMessageContaining("payment URL")
                .hasMessageNotContaining("api-key")
                .hasMessageNotContaining("private-key")

            assertThat(repository.saveCalls).isZero()
        }
    }

    @Test
    fun `HTTP endpoint returns only paymentUrl and does not leak Tripay response or credentials`() {
        WireTripay(successResponse(includeSensitiveNoise = true)).use { wire ->
            val repository = NoSaveRepository()
            val service = service(repository, wire)
            val mvc = MockMvcBuilders.standaloneSetup(
                PaymentGatewaySettingsController(
                    useCase = mock(ManagePaymentGatewaySettingsUseCase::class.java),
                    tripaySandboxTest = service,
                ),
            ).setControllerAdvice(GlobalExceptionHandler()).build()

            val response = mvc.perform(
                post("/api/billing/gateway-settings/tripay/test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                            {
                              "merchantCode": "MERCHANT",
                              "apiKey": "endpoint-api-key",
                              "privateKey": "endpoint-private-key"
                            }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk).andReturn().response

            val json = objectMapper.readTree(response.contentAsString)
            assertThat(json.properties().map { it.key }).containsExactly("paymentUrl")
            assertThat(json.get("paymentUrl").asString()).isEqualTo(PAYMENT_URL)
            assertThat(response.contentAsString).doesNotContain(
                "endpoint-api-key",
                "endpoint-private-key",
                "signature-from-provider",
                "raw-provider-message",
            )
            assertThat(repository.saveCalls).isZero()
        }
    }

    @Test
    fun `endpoint rejects malformed or missing input and requires gateway manage authorization`() {
        WireTripay(successResponse()).use { wire ->
            val service = service(NoSaveRepository(), wire)
            val mvc = MockMvcBuilders.standaloneSetup(
                PaymentGatewaySettingsController(
                    useCase = mock(ManagePaymentGatewaySettingsUseCase::class.java),
                    tripaySandboxTest = service,
                ),
            ).setControllerAdvice(GlobalExceptionHandler()).build()

            mvc.perform(
                post("/api/billing/gateway-settings/tripay/test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isBadRequest)

            mvc.perform(
                post("/api/billing/gateway-settings/tripay/test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"merchantCode":{},"apiKey":"api","privateKey":"private"}"""),
            ).andExpect(status().isBadRequest)

            assertThat(wire.requests).isEmpty()
        }

        val endpoint = PaymentGatewaySettingsController::class.java.getDeclaredMethod(
            "testTripaySandbox",
            TripaySandboxTestRequest::class.java,
        )
        assertThat(endpoint.getAnnotation(PostMapping::class.java).value)
            .containsExactly("/tripay/test")
        assertThat(endpoint.getAnnotation(PreAuthorize::class.java).value)
            .isEqualTo("@authz.can('billing.gateway.manage')")
    }

    private fun service(
        repository: NoSaveRepository,
        wire: WireTripay,
    ): TripaySandboxTestService {
        TenantContext.set(tenantId)
        return TripaySandboxTestService(
            repository = repository,
            tripayGateway = TripayPaymentGateway(
                apiClient = TripayApiClient(
                    objectMapper = objectMapper,
                    sandboxBaseUrl = "${wire.baseUrl}/api-sandbox",
                    productionBaseUrl = "${wire.baseUrl}/api",
                ),
                billingProperties = BillingProperties(
                    tripay = TripayProperties(
                        callbackUrl = "https://app.example.test/api/platform/tripay/callbacks/payment",
                        returnUrl = "https://app.example.test/billing/tripay-test",
                    ),
                ),
            ),
        )
    }

    private fun storedSettings(
        apiKey: String = "old-api-key",
        privateKey: String = "old-private-key",
        sandbox: Boolean = true,
    ): TenantPaymentGateway = TenantPaymentGateway.defaultFor(tenantId).apply {
        update(
            provider = PaymentProvider.TRIPAY,
            enabled = true,
            tripay = TripayPaymentConfig(
                merchantCode = "OLD-MERCHANT",
                apiKey = apiKey,
                privateKey = privateKey,
                sandbox = sandbox,
            ),
        )
    }

    private fun successResponse(
        paymentUrl: String? = PAYMENT_URL,
        includeSensitiveNoise: Boolean = false,
    ): String {
        val checkout = paymentUrl?.let { "\"checkout_url\": \"$it\"," }.orEmpty()
        val noise = if (includeSensitiveNoise) {
            """
                ,"api_key":"endpoint-api-key"
                ,"private_key":"endpoint-private-key"
                ,"signature":"signature-from-provider"
                ,"message":"raw-provider-message"
            """.trimIndent()
        } else {
            ""
        }
        return """
            {
              "success": true,
              "data": {
                "reference": "TREF-SANDBOX-TEST",
                $checkout
                "qr_string": "000201010212TEST",
                "qr_url": "https://qris.example.test/TREF-SANDBOX-TEST.png",
                "status": "UNPAID",
                "expired_time": 1900000000
                $noise
              }
            }
        """.trimIndent()
    }

    private fun sign(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private class NoSaveRepository(
        private val settings: TenantPaymentGateway? = null,
    ) : TenantPaymentGatewayRepository {
        var saveCalls: Int = 0
            private set

        override fun find(): TenantPaymentGateway? = settings

        override fun save(settings: TenantPaymentGateway): TenantPaymentGateway {
            saveCalls += 1
            error("Tripay sandbox test must not save gateway settings")
        }
    }

    private class WireTripay(response: String) : AutoCloseable {
        private val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<RecordedRequest>()
        val request: RecordedRequest get() = requests.last()
        val baseUrl: String = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { exchange -> respond(exchange) }
            server.start()
        }

        private fun respond(exchange: HttpExchange) {
            requests += RecordedRequest(
                path = exchange.requestURI.path,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                form = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use(::parseForm),
            )
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }

        override fun close() = server.stop(0)
    }

    private data class RecordedRequest(
        val path: String,
        val authorization: String?,
        val form: Map<String, String>,
    )

    private companion object {
        const val PAYMENT_URL = "https://checkout.example.test/TREF-SANDBOX-TEST"

        fun parseForm(input: java.io.Reader): Map<String, String> = input.readText()
            .split('&')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val parts = pair.split('=', limit = 2)
                URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                    URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
            }
    }
}
