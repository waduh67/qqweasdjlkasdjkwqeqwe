package com.duluin.ftth.billing

import com.duluin.ftth.billing.adapter.outbound.gateway.PivotPaymentGateway
import com.duluin.ftth.billing.adapter.outbound.gateway.pivot.PivotApiClient
import com.duluin.ftth.billing.adapter.outbound.gateway.pivotExpiryAt
import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.config.PivotProperties
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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

    // --- expiryAt sesi bayar STRICT (jatuh tempo tagihan, di-clamp ke maksimum Pivot) ---

    // Jam tetap agar deterministik (tanpa Instant.now nyata); UTC agar akhir-hari tak tergeser zona.
    private val now = Instant.parse("2026-08-07T03:00:00Z")

    @Test
    fun `tanpa dueDate tidak mengirim expiryAt`() {
        assertThat(pivotExpiryAt(null, now, ZoneOffset.UTC)).isNull()
    }

    @Test
    fun `dueDate normal jadi akhir hari jatuh tempo`() {
        // Jatuh tempo 2026-08-14 → berlaku sampai habis hari itu (awal 08-15 UTC).
        val expiry = pivotExpiryAt(LocalDate.of(2026, 8, 14), now, ZoneOffset.UTC)

        assertThat(expiry).isEqualTo("2026-08-15T00:00:00Z")
    }

    @Test
    fun `dueDate melebihi 30 hari di-clamp ke maksimum Pivot`() {
        // Jatuh tempo 60 hari ke depan → di-clamp ke now+30 hari (batas kartu & virtual account).
        val expiry = pivotExpiryAt(LocalDate.of(2026, 10, 31), now, ZoneOffset.UTC)

        assertThat(expiry).isEqualTo(now.plusSeconds(30 * 24 * 3600).toString())
    }

    @Test
    fun `dueDate sudah lewat di-clamp ke batas bawah agar sesi tak langsung mati`() {
        // Terbit-ulang / overdue: jatuh tempo kemarin → minimal now+1 jam, bukan waktu lampau.
        val expiry = pivotExpiryAt(LocalDate.of(2026, 8, 1), now, ZoneOffset.UTC)

        assertThat(expiry).isEqualTo(now.plusSeconds(3600).toString())
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

    // --- body /v2/payments: redirectUrl WAJIB di kedua mode; X-REQUEST-ID unik per charge ---

    // Gateway dengan redirect-base-url terisi (wajib untuk semua charge, termasuk mode-API).
    private val chargingGateway = PivotPaymentGateway(
        PivotApiClient(objectMapper),
        objectMapper,
        BillingProperties(pivot = PivotProperties(redirectBaseUrl = "https://app.contoh.com/")),
    )

    private fun request(method: String? = null, vaChannel: String? = null) = ChargeRequest(
        invoiceNumber = "INV-202608-0001",
        amount = BigDecimal("150000.00"),
        customerName = "Budi",
        customerEmail = "budi@contoh.com",
        description = "Langganan",
        dueDate = LocalDate.of(2026, 8, 14),
        method = method,
        vaChannel = vaChannel,
    )

    @Test
    fun `body QRIS mode-API tetap menyertakan redirectUrl dan opsi qr`() {
        val body = chargingGateway.buildChargeBody(request(method = "QR"), ctx("cb_key"), "QR")

        assertThat(body["mode"]).isEqualTo("API")
        assertThat(body["autoConfirm"]).isEqualTo(true)
        // redirectUrl WAJIB walau mode-API (Pivot memvalidasinya `required`).
        @Suppress("UNCHECKED_CAST")
        val redirect = body["redirectUrl"] as Map<String, Any>
        assertThat(redirect["successReturnUrl"]).isEqualTo("https://app.contoh.com/paid")
        @Suppress("UNCHECKED_CAST")
        val options = body["paymentMethodOptions"] as Map<String, Any>
        assertThat(options).containsKey("qr")
    }

    @Test
    fun `body Virtual Account mode-API menyertakan redirectUrl dan channel`() {
        val body = chargingGateway.buildChargeBody(
            request(method = "VIRTUAL_ACCOUNT", vaChannel = "BNI"),
            ctx("cb_key"),
            "VIRTUAL_ACCOUNT",
        )

        assertThat(body["mode"]).isEqualTo("API")
        assertThat(body).containsKey("redirectUrl")
        @Suppress("UNCHECKED_CAST")
        val va = (body["paymentMethodOptions"] as Map<String, Any>)["virtualAccount"] as Map<String, Any>
        assertThat(va["channel"]).isEqualTo("BNI")
    }

    @Test
    fun `X-REQUEST-ID unik antar panggilan dan panjangnya masuk rentang alnum`() {
        val a = chargingGateway.newRequestId()
        val b = chargingGateway.newRequestId()

        assertThat(a).isNotEqualTo(b)
        assertThat(a.length).isBetween(16, 36)
        assertThat(a).matches("[A-Za-z0-9]+")
    }
}
