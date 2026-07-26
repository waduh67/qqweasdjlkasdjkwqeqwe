package com.duluin.ftth.collector.adapter

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

/**
 * Klien RADIUS Dynamic Authorization (RFC 5176) murni-Kotlin di atas UDP — tanpa
 * pustaka pihak ketiga, sepadan dengan sikap slim collector.
 *
 * Dipakai adapter FreeRADIUS untuk mengendalikan sesi PPPoE langsung ke BRAS/NAS
 * (bukan ke server RADIUS): Disconnect-Request memutus sesi, CoA-Request mengubah
 * kecepatannya. Authenticator permintaan dihitung gaya Accounting-Request
 * (MD5 atas paket ber-authenticator-nol + shared secret), dan authenticator balasan
 * diverifikasi agar ACK/NAK palsu tak diterima.
 */
object RadiusDae {

    const val DISCONNECT_REQUEST = 40
    const val DISCONNECT_ACK = 41
    const val DISCONNECT_NAK = 42
    const val COA_REQUEST = 43
    const val COA_ACK = 44
    const val COA_NAK = 45

    /** Port DAE baku (RFC 5176) — sengaja konstan, bukan kolom konfigurasi. */
    const val DEFAULT_PORT = 3799

    const val ATTR_USER_NAME = 1
    const val ATTR_NAS_IP_ADDRESS = 4
    const val ATTR_ACCT_SESSION_ID = 44
    const val ATTR_VENDOR_SPECIFIC = 26
    const val ATTR_ERROR_CAUSE = 101

    /** VSA MikroTik: Mikrotik-Rate-Limit "unggah/unduh", BRAS FreeRADIUS paling lazim di ID. */
    const val VENDOR_MIKROTIK = 14988L
    const val MIKROTIK_RATE_LIMIT = 8

    private const val HEADER_LEN = 20

    /** Satu atribut RADIUS (type + nilai mentah). Untuk VSA pakai [vsa]. */
    data class Attribute(val type: Int, val value: ByteArray) {
        override fun equals(other: Any?) =
            this === other || (other is Attribute && type == other.type && value.contentEquals(other.value))

        override fun hashCode() = 31 * type + value.contentHashCode()
    }

    fun userName(value: String) = Attribute(ATTR_USER_NAME, value.toByteArray(StandardCharsets.UTF_8))

    fun acctSessionId(value: String) = Attribute(ATTR_ACCT_SESSION_ID, value.toByteArray(StandardCharsets.UTF_8))

    /** NAS-IP-Address bila [ip] literal IPv4 yang sah; null bila bukan (mis. IPv6/hostname). */
    fun nasIpAddress(ip: String): Attribute? {
        val addr = runCatching { InetAddress.getByName(ip) }.getOrNull()
        return if (addr is Inet4Address) Attribute(ATTR_NAS_IP_ADDRESS, addr.address) else null
    }

    /** Membungkus data vendor-specific (type 26) sesuai encoding RFC 2865 §5.26. */
    fun vsa(vendorId: Long, vendorType: Int, data: ByteArray): Attribute {
        val out = ByteArrayOutputStream()
        out.write(((vendorId ushr 24) and 0xFF).toInt())
        out.write(((vendorId ushr 16) and 0xFF).toInt())
        out.write(((vendorId ushr 8) and 0xFF).toInt())
        out.write((vendorId and 0xFF).toInt())
        out.write(vendorType)
        out.write(2 + data.size) // panjang bagian vendor
        out.write(data)
        return Attribute(ATTR_VENDOR_SPECIFIC, out.toByteArray())
    }

    fun mikrotikRateLimit(upMbps: Int, downMbps: Int): Attribute =
        vsa(VENDOR_MIKROTIK, MIKROTIK_RATE_LIMIT, "${upMbps}M/${downMbps}M".toByteArray(StandardCharsets.UTF_8))

    /**
     * Merangkai paket permintaan lengkap dengan Request Authenticator terisi.
     * Authenticator = MD5(Code | ID | Length | 16 oktet nol | atribut | secret).
     */
    fun buildRequest(code: Int, identifier: Int, secret: String, attributes: List<Attribute>): ByteArray {
        val body = ByteArrayOutputStream()
        for (a in attributes) {
            val len = 2 + a.value.size
            require(len in 3..255) { "atribut RADIUS ${a.type} panjangnya $len di luar rentang" }
            body.write(a.type)
            body.write(len)
            body.write(a.value)
        }
        val attrBytes = body.toByteArray()
        val length = HEADER_LEN + attrBytes.size
        val packet = ByteArray(length)
        packet[0] = code.toByte()
        packet[1] = identifier.toByte()
        packet[2] = ((length ushr 8) and 0xFF).toByte()
        packet[3] = (length and 0xFF).toByte()
        // [4..20) tetap nol saat menghitung authenticator.
        System.arraycopy(attrBytes, 0, packet, HEADER_LEN, attrBytes.size)
        val auth = MessageDigest.getInstance("MD5").run {
            update(packet)
            update(secret.toByteArray(StandardCharsets.UTF_8))
            digest()
        }
        System.arraycopy(auth, 0, packet, 4, 16)
        return packet
    }

    /**
     * Memverifikasi Response Authenticator = MD5(Code | ID | Length |
     * RequestAuthenticator | atribut balasan | secret). [response] harus sudah
     * dipangkas sepanjang byte yang benar-benar diterima.
     */
    fun verifyResponse(response: ByteArray, requestAuthenticator: ByteArray, secret: String): Boolean {
        if (response.size < HEADER_LEN) return false
        val given = response.copyOfRange(4, HEADER_LEN)
        val probe = response.copyOf()
        System.arraycopy(requestAuthenticator, 0, probe, 4, 16)
        val computed = MessageDigest.getInstance("MD5").run {
            update(probe)
            update(secret.toByteArray(StandardCharsets.UTF_8))
            digest()
        }
        return computed.contentEquals(given)
    }

    /** Membaca Error-Cause (atribut 101, 4 oktet) dari balasan NAK, bila ada. */
    fun errorCause(response: ByteArray): Int? {
        var i = HEADER_LEN
        while (i + 2 <= response.size) {
            val type = response[i].toInt() and 0xFF
            val len = response[i + 1].toInt() and 0xFF
            if (len < 2 || i + len > response.size) break
            if (type == ATTR_ERROR_CAUSE && len == 6) {
                return ((response[i + 2].toInt() and 0xFF) shl 24) or
                    ((response[i + 3].toInt() and 0xFF) shl 16) or
                    ((response[i + 4].toInt() and 0xFF) shl 8) or
                    (response[i + 5].toInt() and 0xFF)
            }
            i += len
        }
        return null
    }

    /** Label singkat kode Error-Cause umum RFC 5176 untuk pesan gagal yang mudah dibaca. */
    fun errorCauseLabel(code: Int?): String = when (code) {
        null -> "tanpa Error-Cause"
        401 -> "401 Unsupported Attribute"
        402 -> "402 Missing Attribute"
        403 -> "403 NAS Identification Mismatch"
        404 -> "404 Invalid Request"
        405 -> "405 Unsupported Service"
        501 -> "501 Administratively Prohibited"
        503 -> "503 Session Context Not Found"
        504 -> "504 Session Context Not Removable"
        506 -> "506 Resources Unavailable"
        508 -> "508 Multiple Session Selection Unsupported"
        else -> "Error-Cause $code"
    }
}

/** Balasan DAE terurai: kode paket (ACK/NAK) dan Error-Cause bila NAK. */
data class DaeResult(val code: Int, val errorCause: Int?)

/**
 * Mengirim satu permintaan DAE dan menunggu balasannya, dengan retransmit sederhana
 * (UDP bisa hilang) dan verifikasi authenticator. [socketFactory] disuntik agar bisa
 * diuji terhadap NAS tiruan di loopback.
 */
class RadiusDaeClient(
    private val timeout: Duration = Duration.ofSeconds(3),
    private val retries: Int = 2,
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun send(
        host: String,
        port: Int,
        secret: String,
        code: Int,
        identifier: Int,
        attributes: List<RadiusDae.Attribute>,
    ): DaeResult {
        val request = RadiusDae.buildRequest(code, identifier, secret, attributes)
        val requestAuth = request.copyOfRange(4, 20)
        val address = InetAddress.getByName(host)
        socketFactory().use { socket ->
            socket.soTimeout = timeout.toMillis().toInt()
            var attempt = 0
            while (true) {
                socket.send(DatagramPacket(request, request.size, address, port))
                val buffer = ByteArray(4096)
                val reply = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(reply)
                } catch (_: SocketTimeoutException) {
                    if (attempt++ < retries) {
                        log.debug("DAE {}:{} tak menjawab, kirim ulang ({}/{})", host, port, attempt, retries)
                        continue
                    }
                    throw IllegalStateException("BRAS $host:$port tak menjawab DAE dalam ${timeout.toMillis()}ms")
                }
                val response = buffer.copyOf(reply.length)
                if ((response[1].toInt() and 0xFF) != (identifier and 0xFF)) continue // balasan lama, tunggu lagi
                check(RadiusDae.verifyResponse(response, requestAuth, secret)) {
                    "Authenticator balasan DAE $host:$port tak cocok — shared secret salah?"
                }
                return DaeResult(response[0].toInt() and 0xFF, RadiusDae.errorCause(response))
            }
        }
    }
}
