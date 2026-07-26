package com.duluin.ftth.collector.adapter

import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Menguji inti protokol DAE: encoding paket & atribut yang harus persis benar di kabel,
 * verifikasi authenticator (agar secret salah/paket palsu ditolak), lalu round-trip UDP
 * lengkap terhadap NAS tiruan — termasuk ACK, NAK ber-Error-Cause, timeout, dan retransmit.
 */
class RadiusDaeTest {

    @Test
    fun `buildRequest menuliskan header dan authenticator yang bisa diverifikasi`() {
        val attrs = listOf(RadiusDae.userName("budi@isp"), RadiusDae.acctSessionId("0xABCDEF"))
        val packet = RadiusDae.buildRequest(RadiusDae.DISCONNECT_REQUEST, 7, "rahasia", attrs)

        assertEquals(RadiusDae.DISCONNECT_REQUEST, packet[0].toInt() and 0xFF)
        assertEquals(7, packet[1].toInt() and 0xFF)
        val declaredLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        assertEquals(packet.size, declaredLen)

        // Atribut yang dikirim terbaca kembali utuh.
        assertEquals("budi@isp", RadiusNasStub.stringAttr(packet, RadiusDae.ATTR_USER_NAME))
        assertEquals("0xABCDEF", RadiusNasStub.stringAttr(packet, RadiusDae.ATTR_ACCT_SESSION_ID))
    }

    @Test
    fun `verifyResponse hanya lolos dengan secret yang benar`() {
        // Bangun "balasan" seperti NAS: authenticator atas Request Auth + secret.
        val request = RadiusDae.buildRequest(RadiusDae.COA_REQUEST, 3, "s3cr3t", listOf(RadiusDae.userName("a@isp")))
        val reqAuth = request.copyOfRange(4, 20)
        val response = RadiusNasStubResponse(RadiusDae.COA_ACK, 3, reqAuth, secret = "s3cr3t")
        assertTrue(RadiusDae.verifyResponse(response, reqAuth, "s3cr3t"))
        assertFalse(RadiusDae.verifyResponse(response, reqAuth, "secret-lain"))
    }

    @Test
    fun `mikrotikRateLimit membungkus VSA 14988 dengan nilai unggah-slash-unduh`() {
        val attr = RadiusDae.mikrotikRateLimit(upMbps = 30, downMbps = 100)
        assertEquals(RadiusDae.ATTR_VENDOR_SPECIFIC, attr.type)
        // 4 byte vendor id 14988 = 0x00003A8C, lalu vendorType 8, vendorLen 10, data.
        val v = attr.value
        assertEquals(0x00, v[0].toInt() and 0xFF)
        assertEquals(0x00, v[1].toInt() and 0xFF)
        assertEquals(0x3A, v[2].toInt() and 0xFF)
        assertEquals(0x8C, v[3].toInt() and 0xFF)
        assertEquals(RadiusDae.MIKROTIK_RATE_LIMIT, v[4].toInt() and 0xFF)
        assertEquals(10, v[5].toInt() and 0xFF)
        assertEquals("30M/100M", v.copyOfRange(6, v.size).toString(StandardCharsets.UTF_8))
    }

    @Test
    fun `nasIpAddress hanya untuk IPv4 literal`() {
        val v4 = RadiusDae.nasIpAddress("10.20.0.1")
        assertEquals(RadiusDae.ATTR_NAS_IP_ADDRESS, v4!!.type)
        assertTrue(byteArrayOf(10, 20, 0, 1).contentEquals(v4.value))
        assertNull(RadiusDae.nasIpAddress("::1")) // IPv6 dilewati
    }

    @Test
    fun `errorCause membaca atribut 101 dari balasan NAK`() {
        val reqAuth = ByteArray(16)
        val nak = RadiusNasStubResponse(RadiusDae.DISCONNECT_NAK, 1, reqAuth, secret = "x", errorCause = 503)
        assertEquals(503, RadiusDae.errorCause(nak))
    }

    @Test
    fun `client mengembalikan ACK dari NAS tiruan`() {
        RadiusNasStub("uji-secret") { RadiusNasStub.Reply(RadiusDae.DISCONNECT_ACK) }.start().use { nas ->
            val result = client().send(
                "127.0.0.1", nas.port, "uji-secret", RadiusDae.DISCONNECT_REQUEST, 11,
                listOf(RadiusDae.userName("budi@isp")),
            )
            assertEquals(RadiusDae.DISCONNECT_ACK, result.code)
            assertNull(result.errorCause)
        }
    }

    @Test
    fun `client membawa Error-Cause dari balasan NAK`() {
        RadiusNasStub("uji-secret") { RadiusNasStub.Reply(RadiusDae.COA_NAK, errorCause = 401) }.start().use { nas ->
            val result = client().send(
                "127.0.0.1", nas.port, "uji-secret", RadiusDae.COA_REQUEST, 12,
                listOf(RadiusDae.userName("budi@isp")),
            )
            assertEquals(RadiusDae.COA_NAK, result.code)
            assertEquals(401, result.errorCause)
        }
    }

    @Test
    fun `client menolak balasan dengan authenticator dari secret salah`() {
        // NAS memakai secret berbeda → authenticator balasan takkan cocok.
        RadiusNasStub("secret-nas") { RadiusNasStub.Reply(RadiusDae.DISCONNECT_ACK) }.start().use { nas ->
            val ex = assertFailsWith<IllegalStateException> {
                client().send(
                    "127.0.0.1", nas.port, "secret-collector", RadiusDae.DISCONNECT_REQUEST, 13,
                    listOf(RadiusDae.userName("budi@isp")),
                )
            }
            assertTrue(ex.message!!.contains("Authenticator"), ex.message)
        }
    }

    @Test
    fun `client melempar timeout dan sempat retransmit saat NAS bisu`() {
        RadiusNasStub("uji-secret") { null }.start().use { nas -> // tak pernah membalas
            val ex = assertFailsWith<IllegalStateException> {
                client(retries = 1).send(
                    "127.0.0.1", nas.port, "uji-secret", RadiusDae.DISCONNECT_REQUEST, 14,
                    listOf(RadiusDae.userName("budi@isp")),
                )
            }
            assertTrue(ex.message!!.contains("tak menjawab"), ex.message)
            assertEquals(2, nas.requestCount) // 1 kirim awal + 1 retransmit
        }
    }

    private fun client(retries: Int = 2) =
        RadiusDaeClient(timeout = Duration.ofMillis(200), retries = retries)

    /** Helper: bangun balasan RADIUS seperti NAS (authenticator sah atas Request Auth + secret). */
    @Suppress("TestFunctionName")
    private fun RadiusNasStubResponse(
        code: Int,
        id: Int,
        requestAuth: ByteArray,
        secret: String,
        errorCause: Int? = null,
    ): ByteArray {
        val attrs = if (errorCause == null) {
            ByteArray(0)
        } else {
            byteArrayOf(
                101, 6,
                ((errorCause ushr 24) and 0xFF).toByte(),
                ((errorCause ushr 16) and 0xFF).toByte(),
                ((errorCause ushr 8) and 0xFF).toByte(),
                (errorCause and 0xFF).toByte(),
            )
        }
        val length = 20 + attrs.size
        val packet = ByteArray(length)
        packet[0] = code.toByte()
        packet[1] = id.toByte()
        packet[2] = ((length ushr 8) and 0xFF).toByte()
        packet[3] = (length and 0xFF).toByte()
        System.arraycopy(requestAuth, 0, packet, 4, 16)
        System.arraycopy(attrs, 0, packet, 20, attrs.size)
        val auth = java.security.MessageDigest.getInstance("MD5").run {
            update(packet)
            update(secret.toByteArray(StandardCharsets.UTF_8))
            digest()
        }
        System.arraycopy(auth, 0, packet, 4, 16)
        return packet
    }
}
