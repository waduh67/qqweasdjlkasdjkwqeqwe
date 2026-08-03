package com.duluin.ftth.billing

import com.duluin.ftth.billing.adapter.outbound.gateway.PaywuzPaymentGateway
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Uji verifikasi callback Paywuz tanpa Spring/HTTP: hanya [PaywuzPaymentGateway.parseCallback],
 * fungsi murni header+body → settlement. Fokus penjaga keamanan — tanda tangan HMAC & status yang
 * salah TAK boleh melahirkan pelunasan. Tanda tangan sah dihitung di test dengan skema yang sama
 * (HMAC-SHA256 memakai API key sebagai secret).
 */
class PaywuzPaymentGatewayTest {

    private val apiKey = "pk_sand_secret"
    private val gateway = PaywuzPaymentGateway(JsonMapper.builder().build(), BillingProperties())

    private fun ctx(key: String?) = ResolvedGatewayContext(
        provider = "PAYWUZ",
        mode = GatewayMode.BYO,
        secretKey = key, // API key = Bearer + secret HMAC webhook
        webhookToken = null,
    )

    private fun bodyJson(status: String, order: String = "INV-202608-0001", amount: Long = 150_000, ts: String = "2026-08-01T10:15:30Z") =
        """{"id":"trx_1","orderId":"$order","amount":$amount,"fee":2000,"totalPayment":${amount + 2000},"paymentMethod":"QRIS","status":"$status","timestamp":"$ts"}"""

    private fun sign(body: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun callback(body: String, signature: String?) = GatewayCallback(
        headers = if (signature == null) emptyMap() else mapOf("X-Paywuz-Signature" to signature),
        rawBody = body,
    )

    @Test
    fun `tanda tangan sah dan status settlement melahirkan settlement`() {
        val body = bodyJson("settlement")
        val settlement = gateway.parseCallback(callback(body, sign(body, apiKey)), ctx(apiKey))

        assertThat(settlement).isNotNull
        assertThat(settlement!!.invoiceNumber).isEqualTo("INV-202608-0001")
        assertThat(settlement.gatewayRef).isEqualTo("trx_1")
        assertThat(settlement.amount).isEqualByComparingTo("150000")
        assertThat(settlement.paidAt).isEqualTo(Instant.parse("2026-08-01T10:15:30Z"))
        assertThat(settlement.provider).isEqualTo("PAYWUZ")
    }

    @Test
    fun `status success juga dianggap pelunasan`() {
        val body = bodyJson("success")
        assertThat(gateway.parseCallback(callback(body, sign(body, apiKey)), ctx(apiKey))).isNotNull
    }

    @Test
    fun `tanda tangan salah ditolak`() {
        val body = bodyJson("settlement")
        assertThat(gateway.parseCallback(callback(body, sign(body, "kunci_lain")), ctx(apiKey))).isNull()
    }

    @Test
    fun `header tanda tangan hilang ditolak`() {
        val body = bodyJson("settlement")
        assertThat(gateway.parseCallback(callback(body, null), ctx(apiKey))).isNull()
    }

    @Test
    fun `status belum lunas diabaikan meski tanda tangan sah`() {
        val body = bodyJson("pending")
        assertThat(gateway.parseCallback(callback(body, sign(body, apiKey)), ctx(apiKey))).isNull()
    }

    @Test
    fun `API key tenant belum diset menolak semua callback`() {
        val body = bodyJson("settlement")
        assertThat(gateway.parseCallback(callback(body, sign(body, apiKey)), ctx(null))).isNull()
    }
}
