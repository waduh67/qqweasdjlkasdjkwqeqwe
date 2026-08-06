package com.duluin.ftth.billing

import com.duluin.ftth.billing.adapter.outbound.gateway.PivotPaymentGateway
import com.duluin.ftth.billing.adapter.outbound.gateway.pivot.PivotApiClient
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * Uji verifikasi callback Pivot tanpa Spring/HTTP: hanya [PivotPaymentGateway.parseCallback],
 * fungsi murni header+body → settlement. Fokus penjaga keamanan — X-API-Key & status yang salah
 * TAK boleh melahirkan pelunasan.
 */
class PivotPaymentGatewayTest {

    // parseCallback murni header+body → tak menyentuh HTTP; PivotApiClient hanya melengkapi konstruktor.
    private val objectMapper = JsonMapper.builder().build()
    private val gateway = PivotPaymentGateway(PivotApiClient(objectMapper), objectMapper, BillingProperties())

    private fun ctx(token: String?) = ResolvedGatewayContext(
        provider = "PIVOT",
        mode = GatewayMode.BYO,
        secretKey = "merchant_secret",
        webhookToken = token,
        apiKey = "merchant_id",
    )

    private fun callback(
        apiKey: String?,
        status: String,
        ref: String = "INV-202608-0001",
        amount: Long = 150_000,
        paidAt: String = "2026-08-01T10:15:30Z",
    ) = GatewayCallback(
        headers = if (apiKey == null) emptyMap() else mapOf("X-API-Key" to apiKey),
        rawBody = """
            {"event":"PAYMENT.PAID","data":{"id":"pay_1","clientReferenceId":"$ref",
            "status":"$status","amount":{"value":$amount,"currency":"IDR"},
            "chargeDetails":[{"paidAt":"$paidAt"}]}}
        """.trimIndent(),
    )

    @Test
    fun `X-API-Key benar dan status PAID melahirkan settlement`() {
        val settlement = gateway.parseCallback(callback("cb_key", "PAID"), ctx("cb_key"))

        assertThat(settlement).isNotNull
        assertThat(settlement!!.invoiceNumber).isEqualTo("INV-202608-0001")
        assertThat(settlement.gatewayRef).isEqualTo("pay_1")
        assertThat(settlement.amount).isEqualByComparingTo("150000")
        assertThat(settlement.paidAt).isEqualTo(Instant.parse("2026-08-01T10:15:30Z"))
        assertThat(settlement.provider).isEqualTo("PIVOT")
    }

    @Test
    fun `X-API-Key salah ditolak`() {
        assertThat(gateway.parseCallback(callback("salah", "PAID"), ctx("cb_key"))).isNull()
    }

    @Test
    fun `header X-API-Key hilang ditolak`() {
        assertThat(gateway.parseCallback(callback(null, "PAID"), ctx("cb_key"))).isNull()
    }

    @Test
    fun `status bukan pelunasan diabaikan meski X-API-Key benar`() {
        assertThat(gateway.parseCallback(callback("cb_key", "PROCESSING"), ctx("cb_key"))).isNull()
    }

    @Test
    fun `Callback API Key tenant belum diset menolak semua callback`() {
        assertThat(gateway.parseCallback(callback("cb_key", "PAID"), ctx(null))).isNull()
    }

    @Test
    fun `paidAt jatuh ke now bila chargeDetails tak berisi waktu`() {
        val body = GatewayCallback(
            headers = mapOf("x-api-key" to "cb_key"), // header case-insensitive
            rawBody = """{"event":"PAYMENT.PAID","data":{"id":"pay_2","clientReferenceId":"INV-1","status":"PAID","amount":{"value":1000}}}""",
        )
        val before = Instant.now()

        val settlement = gateway.parseCallback(body, ctx("cb_key"))

        assertThat(settlement).isNotNull
        assertThat(settlement!!.amount).isEqualByComparingTo("1000")
        assertThat(settlement.paidAt).isAfterOrEqualTo(before)
    }
}
