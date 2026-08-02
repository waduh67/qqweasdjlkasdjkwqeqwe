package com.duluin.ftth.billing

import com.duluin.ftth.billing.adapter.outbound.gateway.XenditPaymentGateway
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * Uji verifikasi callback Xendit tanpa Spring/HTTP: hanya [XenditPaymentGateway.parseCallback],
 * yang murni fungsi header+body → settlement. Fokusnya penjaga keamanan — token & status yang
 * salah TAK boleh melahirkan pelunasan.
 */
class XenditPaymentGatewayTest {

    private val gateway = XenditPaymentGateway(JsonMapper.builder().build(), BillingProperties())

    private fun ctx(token: String?) = ResolvedGatewayContext(
        provider = "XENDIT",
        mode = GatewayMode.BYO,
        secretKey = "xnd_secret",
        webhookToken = token,
    )

    private fun callback(token: String?, status: String, extern: String = "INV-202608-0001", amount: String = "150000") =
        GatewayCallback(
            headers = if (token == null) emptyMap() else mapOf("x-callback-token" to token),
            rawBody = """{"id":"inv_1","external_id":"$extern","status":"$status","amount":$amount,"paid_amount":$amount}""",
        )

    @Test
    fun `token benar dan status PAID melahirkan settlement`() {
        val settlement = gateway.parseCallback(callback("tok123", "PAID"), ctx("tok123"))

        assertThat(settlement).isNotNull
        assertThat(settlement!!.invoiceNumber).isEqualTo("INV-202608-0001")
        assertThat(settlement.gatewayRef).isEqualTo("inv_1")
        assertThat(settlement.amount).isEqualByComparingTo("150000")
        assertThat(settlement.provider).isEqualTo("XENDIT")
    }

    @Test
    fun `status SETTLED juga dianggap pelunasan`() {
        val settlement = gateway.parseCallback(callback("tok123", "SETTLED"), ctx("tok123"))

        assertThat(settlement).isNotNull
        assertThat(settlement!!.amount).isEqualByComparingTo("150000")
    }

    @Test
    fun `token salah ditolak`() {
        val settlement = gateway.parseCallback(callback("salah", "PAID"), ctx("tok123"))

        assertThat(settlement).isNull()
    }

    @Test
    fun `header token hilang ditolak`() {
        val settlement = gateway.parseCallback(callback(null, "PAID"), ctx("tok123"))

        assertThat(settlement).isNull()
    }

    @Test
    fun `status bukan pelunasan diabaikan meski token benar`() {
        val settlement = gateway.parseCallback(callback("tok123", "EXPIRED"), ctx("tok123"))

        assertThat(settlement).isNull()
    }

    @Test
    fun `token verifikasi tenant belum diset menolak semua callback`() {
        val settlement = gateway.parseCallback(callback("tok123", "PAID"), ctx(null))

        assertThat(settlement).isNull()
    }

    @Test
    fun `paid_at dari body dipakai bila ada`() {
        val body = GatewayCallback(
            headers = mapOf("x-callback-token" to "tok123"),
            rawBody = """{"id":"inv_1","external_id":"INV-1","status":"PAID","amount":1000,"paid_at":"2026-08-01T10:15:30Z"}""",
        )

        val settlement = gateway.parseCallback(body, ctx("tok123"))

        assertThat(settlement).isNotNull
        assertThat(settlement!!.paidAt).isEqualTo(Instant.parse("2026-08-01T10:15:30Z"))
    }
}
