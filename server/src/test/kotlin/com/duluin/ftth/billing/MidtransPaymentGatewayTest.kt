package com.duluin.ftth.billing

import com.duluin.ftth.billing.adapter.outbound.gateway.MidtransPaymentGateway
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/**
 * Uji verifikasi callback Midtrans tanpa Spring/HTTP: hanya [MidtransPaymentGateway.parseCallback],
 * fungsi murni body → settlement. Fokus penjaga keamanan — signature SHA512 & status yang salah TAK
 * boleh melahirkan pelunasan. Signature sah dihitung di test dengan skema yang sama
 * (SHA512(order_id + status_code + gross_amount + serverKey)).
 */
class MidtransPaymentGatewayTest {

    private val serverKey = "SB-Mid-server-abcdef123456"
    private val gateway = MidtransPaymentGateway(JsonMapper.builder().build())

    private fun ctx(key: String?) = ResolvedGatewayContext(
        provider = "MIDTRANS",
        mode = GatewayMode.BYO,
        secretKey = key, // Server Key = basic-auth + secret signature
        webhookToken = null,
    )

    private fun sha512Hex(message: String): String =
        MessageDigest.getInstance("SHA-512")
            .digest(message.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun bodyJson(
        transactionStatus: String,
        order: String = "SUB-202608-a1b2c3d4",
        statusCode: String = "200",
        grossAmount: String = "150000.00",
        fraudStatus: String? = "accept",
        signature: String? = sha512Hex(order + statusCode + grossAmount + serverKey),
        settlementTime: String = "2026-08-01 10:15:30",
    ): String = buildString {
        append("{")
        append(""""order_id":"$order",""")
        append(""""status_code":"$statusCode",""")
        append(""""gross_amount":"$grossAmount",""")
        append(""""transaction_id":"mt_trx_1",""")
        append(""""transaction_status":"$transactionStatus",""")
        if (fraudStatus != null) append(""""fraud_status":"$fraudStatus",""")
        append(""""settlement_time":"$settlementTime"""")
        if (signature != null) append(""","signature_key":"$signature"""")
        append("}")
    }

    private fun callback(body: String) = GatewayCallback(headers = emptyMap(), rawBody = body)

    @Test
    fun `signature sah dan status settlement melahirkan settlement`() {
        val settlement = gateway.parseCallback(callback(bodyJson("settlement")), ctx(serverKey))

        assertThat(settlement).isNotNull
        assertThat(settlement!!.invoiceNumber).isEqualTo("SUB-202608-a1b2c3d4")
        assertThat(settlement.gatewayRef).isEqualTo("mt_trx_1")
        assertThat(settlement.amount).isEqualByComparingTo("150000")
        assertThat(settlement.provider).isEqualTo("MIDTRANS")
        // settlement_time WIB (UTC+7) → 03:15:30 UTC.
        assertThat(settlement.paidAt).isEqualTo(Instant.parse("2026-08-01T03:15:30Z"))
    }

    @Test
    fun `capture dengan fraud accept dianggap pelunasan`() {
        assertThat(gateway.parseCallback(callback(bodyJson("capture", fraudStatus = "accept")), ctx(serverKey))).isNotNull
    }

    @Test
    fun `capture dengan fraud challenge diabaikan`() {
        assertThat(gateway.parseCallback(callback(bodyJson("capture", fraudStatus = "challenge")), ctx(serverKey))).isNull()
    }

    @Test
    fun `signature salah ditolak`() {
        val body = bodyJson("settlement", signature = sha512Hex("dipalsukan"))
        assertThat(gateway.parseCallback(callback(body), ctx(serverKey))).isNull()
    }

    @Test
    fun `signature hilang ditolak`() {
        assertThat(gateway.parseCallback(callback(bodyJson("settlement", signature = null)), ctx(serverKey))).isNull()
    }

    @Test
    fun `status pending diabaikan meski signature sah`() {
        assertThat(gateway.parseCallback(callback(bodyJson("pending")), ctx(serverKey))).isNull()
    }

    @Test
    fun `server key belum diset menolak semua callback`() {
        assertThat(gateway.parseCallback(callback(bodyJson("settlement")), ctx(null))).isNull()
    }
}
