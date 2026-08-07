package com.duluin.ftth.simulator.radius

import com.duluin.ftth.contract.radius.RadiusDae
import com.duluin.ftth.contract.radius.RadiusDaeClient
import java.net.DatagramSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bukti kesetiaan protokol DAE: klien [RadiusDaeClient] **produksi** (yang server pakai untuk
 * isolir/Reset Login/CoA) menembak [DaeResponder] lewat soket UDP nyata. Klien memverifikasi
 * Response Authenticator secara internal, jadi setiap balasan yang tak dilempar membuktikan
 * responder menandatanganinya dengan benar memakai shared secret.
 */
class DaeResponderTest {

    private val secret = "rahasia-lab-123"
    private var responder: DaeResponder? = null

    @AfterTest
    fun tearDown() {
        responder?.stop()
    }

    private fun start(control: NasSessionControl): Int {
        val port = freeUdpPort()
        responder = DaeResponder("127.0.0.1", port, secret, control).also { it.start() }
        return port
    }

    @Test
    fun `Disconnect sesi hidup dibalas ACK dan meneruskan username + acct-session-id`() {
        val calls = mutableListOf<Pair<String, String?>>()
        val port = start(object : NasSessionControl {
            override fun disconnect(username: String, acctSessionId: String?): Boolean {
                calls += username to acctSessionId
                return true
            }
            override fun changeRate(username: String, acctSessionId: String?) = true
        })

        val result = RadiusDaeClient().send(
            "127.0.0.1", port, secret, RadiusDae.DISCONNECT_REQUEST, 7,
            listOf(RadiusDae.userName("acme:budi"), RadiusDae.acctSessionId("sess-1")),
        )

        assertEquals(RadiusDae.DISCONNECT_ACK, result.code)
        assertNull(result.errorCause)
        assertEquals(listOf<Pair<String, String?>>("acme:budi" to "sess-1"), calls)
    }

    @Test
    fun `Disconnect sesi tak ada dibalas NAK dengan Error-Cause 503`() {
        val port = start(object : NasSessionControl {
            override fun disconnect(username: String, acctSessionId: String?) = false
            override fun changeRate(username: String, acctSessionId: String?) = false
        })

        val result = RadiusDaeClient().send(
            "127.0.0.1", port, secret, RadiusDae.DISCONNECT_REQUEST, 9,
            listOf(RadiusDae.userName("acme:hilang")),
        )

        assertEquals(RadiusDae.DISCONNECT_NAK, result.code)
        assertEquals(503, result.errorCause)
    }

    @Test
    fun `CoA sesi hidup dibalas ACK`() {
        val port = start(object : NasSessionControl {
            override fun disconnect(username: String, acctSessionId: String?) = true
            override fun changeRate(username: String, acctSessionId: String?) = true
        })

        val result = RadiusDaeClient().send(
            "127.0.0.1", port, secret, RadiusDae.COA_REQUEST, 11,
            listOf(RadiusDae.userName("acme:budi"), RadiusDae.mikrotikRateLimit(5, 50)),
        )

        assertEquals(RadiusDae.COA_ACK, result.code)
    }

    @Test
    fun `secret salah tak dibalas sehingga klien timeout`() {
        val port = start(object : NasSessionControl {
            override fun disconnect(username: String, acctSessionId: String?) = true
            override fun changeRate(username: String, acctSessionId: String?) = true
        })

        // Klien retry cepat lalu menyerah — verifikasi responder MENGABAIKAN secret salah.
        val client = RadiusDaeClient(timeout = java.time.Duration.ofMillis(300), retries = 1)
        val failure = runCatching {
            client.send(
                "127.0.0.1", port, "secret-yang-salah", RadiusDae.DISCONNECT_REQUEST, 13,
                listOf(RadiusDae.userName("acme:budi")),
            )
        }
        assertTrue(failure.isFailure, "secret salah harus tak dibalas → klien gagal, bukan menerima ACK")
    }

    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }
}
