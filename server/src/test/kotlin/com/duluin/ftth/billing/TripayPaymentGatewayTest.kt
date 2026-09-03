package com.duluin.ftth.billing

import com.duluin.ftth.billing.adapter.outbound.gateway.TripayPaymentGateway
import com.duluin.ftth.billing.adapter.outbound.gateway.tripay.TripayApiClient
import com.duluin.ftth.billing.adapter.outbound.gateway.tripay.TripayCredentials
import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.config.TripayProperties
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.common.domain.error.ConflictException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.Duration
import java.util.stream.Stream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TripayPaymentGatewayTest {

    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun `sandbox VA charge sends official form, bearer authentication, and exact merchant signature`() {
        WireTripay(vaResponse()).use { wire ->
            val result = gateway(wire).createCharge(request(method = "VIRTUAL_ACCOUNT", channel = "BNI"), ctx(sandbox = true))

            assertThat(wire.request.method).isEqualTo("POST")
            assertThat(wire.request.path).isEqualTo("/api-sandbox/transaction/create")
            assertThat(wire.request.authorization).isEqualTo("Bearer fixture-api-key")
            assertThat(wire.request.form).containsEntry("method", "BNIVA")
            assertThat(wire.request.form).containsEntry("merchant_ref", "INV-202609-0001")
            assertThat(wire.request.form).containsEntry("amount", "150000")
            assertThat(wire.request.form).containsEntry("customer_name", "Budi")
            assertThat(wire.request.form).containsEntry("customer_email", "budi@example.test")
            assertThat(wire.request.form).containsEntry("callback_url", "https://app.example.test/api/platform/tripay/callbacks/payment")
            assertThat(wire.request.form).containsEntry("return_url", "https://app.example.test/invoices/paid")
            assertThat(wire.request.form).containsEntry("order_items[0][sku]", "INV-202609-0001")
            assertThat(wire.request.form).containsEntry("order_items[0][name]", "September service")
            assertThat(wire.request.form).containsEntry("order_items[0][price]", "150000")
            assertThat(wire.request.form).containsEntry("order_items[0][quantity]", "1")
            assertThat(wire.request.form).containsEntry(
                "signature",
                "c80d73312d91739fd81eead463d2816ddfd9b062676c74e8c280a7b8fa092c07",
            )

            assertThat(result.provider).isEqualTo("TRIPAY")
            assertThat(result.gatewayRef).isEqualTo("TREF-VA")
            assertThat(result.payUrl).isEqualTo("https://checkout.example.test/TREF-VA")
            assertThat(result.status).isEqualTo("UNPAID")
            assertThat(result.method).isEqualTo("VIRTUAL_ACCOUNT")
            assertThat(result.virtualAccount).isNotNull
            val virtualAccount = checkNotNull(result.virtualAccount)
            assertThat(virtualAccount.channel).isEqualTo("BNI")
            assertThat(virtualAccount.number).isEqualTo("880812341234")
            assertThat(virtualAccount.name).isEqualTo("BNI Virtual Account")
            assertThat(virtualAccount.expiresAt).isEqualTo(Instant.ofEpochSecond(1_788_000_000))
            assertThat(result.qr).isNull()
        }
    }

    @Test
    fun `production QR charge uses production endpoint and maps QR instruction`() {
        WireTripay(qrResponse()).use { wire ->
            val result = gateway(wire).createCharge(request(method = "QR"), ctx(sandbox = false))

            assertThat(wire.request.path).isEqualTo("/api/transaction/create")
            assertThat(wire.request.form).containsEntry("method", "QRIS")
            assertThat(result.provider).isEqualTo("TRIPAY")
            assertThat(result.gatewayRef).isEqualTo("TREF-QR")
            assertThat(result.method).isEqualTo("QR")
            assertThat(result.qr).isNotNull
            val qr = checkNotNull(result.qr)
            assertThat(qr.content).isEqualTo("00020101021226670016COM.NOBUBANK.WWW01189360050300000879140214570000000000010303UMI51440014ID.CO.QRIS.WWW0215ID10221612967010303UMI5204541153033605802ID5914BUDI INTERNET6007JAKARTA6105123456304ABCD")
            assertThat(qr.url).isEqualTo("https://qris.example.test/TREF-QR.png")
            assertThat(qr.expiresAt).isEqualTo(Instant.ofEpochSecond(1_788_000_600))
            assertThat(result.virtualAccount).isNull()
        }
    }

    @ParameterizedTest(name = "{0} maps to {1}")
    @MethodSource("virtualAccountMethods")
    fun `supported VA catalog channels map to their Tripay method codes`(channel: String, tripayCode: String) {
        WireTripay(vaResponse()).use { wire ->
            val result = gateway(wire).createCharge(request(method = "VIRTUAL_ACCOUNT", channel = channel), ctx(sandbox = true))

            assertThat(wire.request.form).containsEntry("method", tripayCode)
            assertThat(checkNotNull(result.virtualAccount).channel).isEqualTo(channel)
        }
    }

    @Test
    fun `same adapter chooses endpoint from the current sandbox setting for every charge`() {
        WireTripay(qrResponse()).use { wire ->
            val adapter = gateway(wire)

            adapter.createCharge(request(method = "QR"), ctx(sandbox = true))
            adapter.createCharge(request(method = "QR"), ctx(sandbox = false))

            assertThat(wire.requests.map { it.path })
                .containsExactly("/api-sandbox/transaction/create", "/api/transaction/create")
        }
    }

    @Test
    fun `Tripay false success response with data is rejected without exposing credentials`() {
        WireTripay(unsuccessfulResponseWithData()).use { wire ->
            val thrown = checkNotNull(catchThrowable { gateway(wire).createCharge(request(method = "QR"), ctx(sandbox = true)) })

            assertThat(thrown).isInstanceOf(ConflictException::class.java)
            assertThat(thrown.message).doesNotContain("fixture-api-key", "fixture-private-key")
        }
    }

    @Test
    fun `Tripay successful response without a reference is rejected as malformed`() {
        WireTripay(qrResponse().replace("\"reference\": \"TREF-QR\",", "")).use { wire ->
            val thrown = catchThrowable { gateway(wire).createCharge(request(method = "QR"), ctx(sandbox = true)) }

            assertThat(thrown).isInstanceOf(ConflictException::class.java)
        }
    }

    @Test
    fun `Tripay response with an out of range expiration is rejected as malformed`() {
        WireTripay(qrResponse().replace("1788000600", "9223372036854775807")).use { wire ->
            val thrown = checkNotNull(catchThrowable {
                gateway(wire).createCharge(request(method = "QR"), ctx(sandbox = true))
            })

            assertThat(thrown).isInstanceOf(ConflictException::class.java)
        }
    }

    @Test
    fun `Tripay callback settles a PAID transaction only when its exact raw body is signed`() {
        val rawBody = paidCallbackResponse()
        WireTripay(qrResponse()).use { wire ->
            val settlement = checkNotNull(gateway(wire).parseCallback(
                GatewayCallback(
                    headers = mapOf("X-Callback-Signature" to callbackSignature(rawBody)),
                    rawBody = rawBody,
                ),
                ctx(sandbox = true),
            ))

            assertThat(settlement).isNotNull
            assertThat(settlement.invoiceNumber).isEqualTo("INV-202609-0001")
            assertThat(settlement.gatewayRef).isEqualTo("TREF-CALLBACK")
            assertThat(settlement.amount).isEqualByComparingTo("150000")
            assertThat(settlement.paidAt).isEqualTo(Instant.ofEpochSecond(1_788_000_900))
            assertThat(settlement.provider).isEqualTo("TRIPAY")
        }
    }

    @Test
    fun `Tripay callback rejects a signature for different raw bytes`() {
        val rawBody = paidCallbackResponse()
        WireTripay(qrResponse()).use { wire ->
            val settlement = gateway(wire).parseCallback(
                GatewayCallback(
                    headers = mapOf("X-Callback-Signature" to callbackSignature(rawBody)),
                    rawBody = "$rawBody\n",
                ),
                ctx(sandbox = true),
            )

            assertThat(settlement).isNull()
        }
    }

    @Test
    fun `Tripay callback does not settle a signed non-PAID transaction`() {
        val rawBody = paidCallbackResponse().replace("\"status\": \"PAID\"", "\"status\": \"UNPAID\"")
        WireTripay(qrResponse()).use { wire ->
            val settlement = gateway(wire).parseCallback(
                GatewayCallback(
                    headers = mapOf("X-Callback-Signature" to callbackSignature(rawBody)),
                    rawBody = rawBody,
                ),
                ctx(sandbox = true),
            )

            assertThat(settlement).isNull()
        }
    }

    @Test
    fun `Tripay delayed response read failure becomes a safe conflict without credential text`() {
        WireTripay(qrResponse(), responseDelay = Duration.ofMillis(250)).use { wire ->
            val thrown = checkNotNull(catchThrowable {
                gateway(wire, readTimeout = Duration.ofMillis(50)).createCharge(request(method = "QR"), ctx(sandbox = true))
            })

            assertThat(thrown).isInstanceOf(ConflictException::class.java)
            assertThat(thrown.message)
                .contains("Tripay mengembalikan respons create transaction yang tidak dapat diproses")
                .doesNotContain("fixture-api-key", "fixture-private-key")
        }
    }

    @Test
    fun `Tripay HTTP error becomes safe conflict without credential text`() {
        WireTripay("{\"success\":false,\"message\":\"request rejected\"}", status = 422).use { wire ->
            val thrown = checkNotNull(catchThrowable { gateway(wire).createCharge(request(method = "QR"), ctx(sandbox = true)) })

            assertThat(thrown).isInstanceOf(ConflictException::class.java)
            assertThat(thrown.message).contains("Tripay menolak create transaction (422)")
            assertThat(thrown.message).doesNotContain("fixture-api-key", "fixture-private-key")
        }
    }

    @Test
    fun `blank deployment URL and unsupported VA channel are rejected before an HTTP request`() {
        WireTripay(vaResponse()).use { wire ->
            val missingUrl = checkNotNull(catchThrowable {
                gateway(wire, callbackUrl = "   ").createCharge(request(method = "QR"), ctx(sandbox = true))
            })
            val unsupportedChannel = checkNotNull(catchThrowable {
                gateway(wire).createCharge(request(method = "VIRTUAL_ACCOUNT", channel = "DANAMON"), ctx(sandbox = true))
            })

            assertThat(missingUrl).isInstanceOf(ConflictException::class.java)
            assertThat(missingUrl.message).contains("FTTH_BILLING_TRIPAY_CALLBACK_URL")
            assertThat(unsupportedChannel).isInstanceOf(ConflictException::class.java)
            assertThat(unsupportedChannel.message).contains("DANAMON")
            assertThat(wire.requestCount).isZero()
        }
    }

    @Test
    fun `Tripay credential normal representation and Jackson serialization redact secret values`() {
        val credentials = TripayCredentials(
            merchantCode = "MERCHANT-TEST",
            apiKey = "fixture-api-key",
            privateKey = "fixture-private-key",
            sandbox = true,
        )

        val rendered = credentials.toString()
        val serialized = objectMapper.writeValueAsString(credentials)

        assertThat(rendered).contains("apiKeySet=true", "privateKeySet=true")
        assertThat(rendered).doesNotContain("fixture-api-key", "fixture-private-key")
        assertThat(serialized).doesNotContain("fixture-api-key", "fixture-private-key")
    }

    private fun gateway(
        wire: WireTripay,
        callbackUrl: String = "https://app.example.test/api/platform/tripay/callbacks/payment",
        returnUrl: String = "https://app.example.test/invoices/paid",
        readTimeout: Duration = Duration.ofSeconds(20),
    ): TripayPaymentGateway = TripayPaymentGateway(
        apiClient = TripayApiClient(
            objectMapper = objectMapper,
            sandboxBaseUrl = "${wire.baseUrl}/api-sandbox",
            productionBaseUrl = "${wire.baseUrl}/api",
            readTimeout = readTimeout,
        ),
        billingProperties = BillingProperties(
            tripay = TripayProperties(
                callbackUrl = callbackUrl,
                returnUrl = returnUrl,
            ),
        ),
    )

    private fun request(method: String, channel: String? = null) = ChargeRequest(
        invoiceNumber = "INV-202609-0001",
        amount = BigDecimal("150000.00"),
        customerName = "Budi",
        customerEmail = "budi@example.test",
        description = "September service",
        method = method,
        vaChannel = channel,
    )

    private fun ctx(sandbox: Boolean) = ResolvedGatewayContext(
        provider = "TRIPAY",
        mode = GatewayMode.BYO,
        secretKey = "fixture-private-key",
        webhookToken = null,
        apiKey = "fixture-api-key",
        merchantCode = "MERCHANT-TEST",
        sandbox = sandbox,
    )

    private fun vaResponse() = """
        {
          "success": true,
          "data": {
            "reference": "TREF-VA",
            "merchant_ref": "INV-202609-0001",
            "payment_method": "BNIVA",
            "payment_name": "BNI Virtual Account",
            "amount": 150000,
            "pay_code": "880812341234",
            "checkout_url": "https://checkout.example.test/TREF-VA",
            "status": "UNPAID",
            "expired_time": 1788000000
          }
        }
    """.trimIndent()

    private fun qrResponse() = """
        {
          "success": true,
          "data": {
            "reference": "TREF-QR",
            "merchant_ref": "INV-202609-0001",
            "payment_method": "QRIS",
            "payment_name": "QRIS",
            "amount": 150000,
            "qr_string": "00020101021226670016COM.NOBUBANK.WWW01189360050300000879140214570000000000010303UMI51440014ID.CO.QRIS.WWW0215ID10221612967010303UMI5204541153033605802ID5914BUDI INTERNET6007JAKARTA6105123456304ABCD",
            "qr_url": "https://qris.example.test/TREF-QR.png",
            "checkout_url": "https://checkout.example.test/TREF-QR",
            "status": "UNPAID",
            "expired_time": 1788000600
          }
        }
    """.trimIndent()

    private fun unsuccessfulResponseWithData() = qrResponse().replace("\"success\": true", "\"success\": false")

    private fun paidCallbackResponse() = """
        {
          "reference": "TREF-CALLBACK",
          "merchant_ref": "INV-202609-0001",
          "total_amount": 150000,
          "status": "PAID",
          "paid_at": 1788000900
        }
    """.trimIndent()

    private class WireTripay(
        response: String,
        private val status: Int = 200,
        private val responseDelay: Duration = Duration.ZERO,
    ) : AutoCloseable {
        private val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<RecordedRequest>()
        val request: RecordedRequest get() = requests.last()
        val requestCount: Int get() = requests.size

        val baseUrl: String = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { exchange -> respond(exchange) }
            server.start()
        }

        private fun respond(exchange: HttpExchange) {
            requests += RecordedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                form = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use(::parseForm),
            )
            if (!responseDelay.isZero) Thread.sleep(responseDelay.toMillis())
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }

        override fun close() = server.stop(0)
    }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val authorization: String?,
        val form: Map<String, String>,
    )

    private companion object {
        @JvmStatic
        fun virtualAccountMethods(): Stream<Arguments> = Stream.of(
            Arguments.of("BRI", "BRIVA"),
            Arguments.of("BNI", "BNIVA"),
            Arguments.of("MANDIRI", "MANDIRIVA"),
            Arguments.of("BCA", "BCAVA"),
            Arguments.of("BSI", "BSIVA"),
            Arguments.of("CIMB", "CIMBVA"),
            Arguments.of("PERMATA", "PERMATAVA"),
        )

        fun callbackSignature(rawBody: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec("fixture-private-key".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(rawBody.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

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
