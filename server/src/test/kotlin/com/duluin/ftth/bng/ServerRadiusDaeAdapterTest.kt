package com.duluin.ftth.bng

import com.duluin.ftth.bng.adapter.outbound.radius.DaeTransport
import com.duluin.ftth.bng.adapter.outbound.radius.ServerRadiusDaeAdapter
import com.duluin.ftth.contract.radius.DaeResult
import com.duluin.ftth.contract.radius.RadiusDae
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * Menguji keputusan [ServerRadiusDaeAdapter] terhadap transport DAE palsu (tanpa soket UDP):
 * paket yang benar dirakit dari data sesi yang diberikan (User-Name bare + Acct-Session-Id,
 * plus VSA Mikrotik-Rate-Limit untuk CoA), idempotensi Disconnect (NAK 503 = selesai, bukan
 * gagal), dan kegagalan tegas (NAK lain, kode tak terduga). Codec ada di [RadiusDae]
 * (modul contract) & diuji di sana; di sini yang diuji cabang keputusan adapter.
 */
class ServerRadiusDaeAdapterTest {

    @Test
    fun `DISCONNECT merakit Disconnect-Request dengan User-Name, Acct-Session-Id, dan NAS-IP`() {
        val transport = FakeTransport { DaeResult(RadiusDae.DISCONNECT_ACK, null) }
        adapter(transport).disconnect(
            host = "203.0.113.9", secret = "s3cr3t", username = "budi",
            acctSessionId = "0xSID1", nasIp = "10.20.0.1", identifier = 7,
        )

        val sent = transport.sent!!
        assertThat(sent.host).isEqualTo("203.0.113.9")
        assertThat(sent.port).isEqualTo(RadiusDae.DEFAULT_PORT)
        assertThat(sent.code).isEqualTo(RadiusDae.DISCONNECT_REQUEST)
        assertThat(sent.identifier).isEqualTo(7)
        assertThat(sent.attributes.string(RadiusDae.ATTR_USER_NAME)).isEqualTo("budi")
        assertThat(sent.attributes.string(RadiusDae.ATTR_ACCT_SESSION_ID)).isEqualTo("0xSID1")
        assertThat(sent.attributes.firstOrNull { it.type == RadiusDae.ATTR_NAS_IP_ADDRESS }).isNotNull()
    }

    @Test
    fun `DISCONNECT tanpa Acct-Session-Id memakai host sebagai NAS-IP`() {
        val transport = FakeTransport { DaeResult(RadiusDae.DISCONNECT_ACK, null) }
        adapter(transport).disconnect(
            host = "203.0.113.9", secret = "s3cr3t", username = "budi",
            acctSessionId = null, nasIp = null, identifier = 1,
        )

        val sent = transport.sent!!
        assertThat(sent.attributes.firstOrNull { it.type == RadiusDae.ATTR_ACCT_SESSION_ID }).isNull()
        // NAS-IP jatuh ke host (203.0.113.9 literal IPv4) → atribut ada.
        assertThat(sent.attributes.firstOrNull { it.type == RadiusDae.ATTR_NAS_IP_ADDRESS }).isNotNull()
    }

    @Test
    fun `DISCONNECT NAK 503 dianggap selesai (idempoten, tak melempar)`() {
        val transport = FakeTransport { DaeResult(RadiusDae.DISCONNECT_NAK, 503) }
        // Tak melempar = ditelan sebagai selesai; paket tetap terkirim.
        adapter(transport).disconnect("203.0.113.9", "s3cr3t", "budi", "0xSID1", "10.20.0.1", 2)
        assertThat(transport.sent!!.code).isEqualTo(RadiusDae.DISCONNECT_REQUEST)
    }

    @Test
    fun `DISCONNECT NAK selain 503 melempar dengan label sebab`() {
        val transport = FakeTransport { DaeResult(RadiusDae.DISCONNECT_NAK, 501) }
        assertThatThrownBy {
            adapter(transport).disconnect("203.0.113.9", "s3cr3t", "budi", "0xSID1", "10.20.0.1", 3)
        }.hasMessageContaining("501")
    }

    @Test
    fun `DISCONNECT dengan kode balasan tak terduga melempar`() {
        val transport = FakeTransport { DaeResult(RadiusDae.COA_ACK, null) }
        assertThatThrownBy {
            adapter(transport).disconnect("203.0.113.9", "s3cr3t", "budi", "0xSID1", "10.20.0.1", 4)
        }.hasMessageContaining("tak terduga")
    }

    @Test
    fun `CoA merakit CoA-Request dengan VSA Mikrotik-Rate-Limit unggah-slash-unduh`() {
        val transport = FakeTransport { DaeResult(RadiusDae.COA_ACK, null) }
        adapter(transport).changeRate(
            host = "203.0.113.9", secret = "s3cr3t", username = "budi",
            downMbps = 100, upMbps = 30, acctSessionId = "0xSID1", identifier = 5,
        )

        val sent = transport.sent!!
        assertThat(sent.code).isEqualTo(RadiusDae.COA_REQUEST)
        assertThat(sent.attributes.string(RadiusDae.ATTR_USER_NAME)).isEqualTo("budi")
        assertThat(sent.attributes.mikrotikRate()).isEqualTo("30M/100M")
    }

    @Test
    fun `CoA di-NAK melempar dengan label sebab`() {
        val transport = FakeTransport { DaeResult(RadiusDae.COA_NAK, 401) }
        assertThatThrownBy {
            adapter(transport).changeRate("203.0.113.9", "s3cr3t", "budi", 100, 30, "0xSID1", 6)
        }.hasMessageContaining("401")
    }

    // ---- Fixture & fake ----

    private fun adapter(transport: DaeTransport) = ServerRadiusDaeAdapter(transport = transport)

    private class FakeTransport(private val reply: (Int) -> DaeResult) : DaeTransport {
        data class Sent(
            val host: String,
            val port: Int,
            val secret: String,
            val code: Int,
            val identifier: Int,
            val attributes: List<RadiusDae.Attribute>,
        )

        var sent: Sent? = null

        override fun send(
            host: String,
            port: Int,
            secret: String,
            code: Int,
            identifier: Int,
            attributes: List<RadiusDae.Attribute>,
        ): DaeResult {
            sent = Sent(host, port, secret, code, identifier, attributes)
            return reply(code)
        }
    }

    private fun List<RadiusDae.Attribute>.string(type: Int): String? =
        firstOrNull { it.type == type }?.value?.toString(StandardCharsets.UTF_8)

    /** Ekstrak nilai VSA Mikrotik-Rate-Limit (14988/8): lewati 4 byte vendor + type + len. */
    private fun List<RadiusDae.Attribute>.mikrotikRate(): String? =
        firstOrNull { it.type == RadiusDae.ATTR_VENDOR_SPECIFIC }
            ?.value?.let { it.copyOfRange(6, it.size).toString(StandardCharsets.UTF_8) }
}
