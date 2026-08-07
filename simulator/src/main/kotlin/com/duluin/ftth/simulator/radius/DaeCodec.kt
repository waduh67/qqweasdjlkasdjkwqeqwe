package com.duluin.ftth.simulator.radius

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Codec sisi-NAS untuk paket DAE (RFC 5176) — kebalikan dari
 * [com.duluin.ftth.contract.radius.RadiusDae] yang dipakai sisi-pengirim (server).
 *
 * Sengaja ditulis di sini, bukan menambah kode server ke `contract`, agar peran "menjawab
 * DAE" tetap milik simulator dan tak bocor ke jalur produksi. Cukup ~selusin baris: memparse
 * atribut request, memverifikasi Request Authenticator (menolak secret salah), dan merangkai
 * balasan dengan Response Authenticator yang benar sehingga `RadiusDae.verifyResponse` di sisi
 * server menerimanya.
 */
object DaeCodec {

    private const val HEADER_LEN = 20
    private const val ATTR_ERROR_CAUSE = 101

    fun code(packet: ByteArray): Int = packet[0].toInt() and 0xFF
    fun identifier(packet: ByteArray): Int = packet[1].toInt() and 0xFF
    fun authenticator(packet: ByteArray): ByteArray = packet.copyOfRange(4, HEADER_LEN)

    /** Urai atribut (type → nilai mentah), berurutan sesuai wire. */
    fun attributes(packet: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var i = HEADER_LEN
        while (i + 2 <= packet.size) {
            val type = packet[i].toInt() and 0xFF
            val len = packet[i + 1].toInt() and 0xFF
            if (len < 2 || i + len > packet.size) break
            out += type to packet.copyOfRange(i + 2, i + len)
            i += len
        }
        return out
    }

    /** Nilai atribut string pertama bertipe [type], atau null bila absen. */
    fun stringAttr(attrs: List<Pair<Int, ByteArray>>, type: Int): String? =
        attrs.firstOrNull { it.first == type }?.second?.toString(StandardCharsets.UTF_8)

    /**
     * Verifikasi Request Authenticator = MD5(Code|ID|Length|16 oktet nol|atribut|secret) —
     * rumus [com.duluin.ftth.contract.radius.RadiusDae.buildRequest]. Secret salah → false,
     * dan responder mengabaikan paket (persis BRAS nyata yang diam).
     */
    fun verifyRequest(packet: ByteArray, secret: String): Boolean {
        if (packet.size < HEADER_LEN) return false
        val given = packet.copyOfRange(4, HEADER_LEN)
        val probe = packet.copyOf()
        for (i in 4 until HEADER_LEN) probe[i] = 0
        return md5(probe, secret).contentEquals(given)
    }

    /**
     * Rangkai balasan dengan Response Authenticator =
     * MD5(Code|ID|Length|RequestAuthenticator|atribut balasan|secret), sesuai yang diverifikasi
     * [com.duluin.ftth.contract.radius.RadiusDae.verifyResponse].
     */
    fun buildResponse(
        code: Int,
        identifier: Int,
        requestAuthenticator: ByteArray,
        secret: String,
        attributes: List<Pair<Int, ByteArray>>,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        for ((type, value) in attributes) {
            body.write(type)
            body.write(2 + value.size)
            body.write(value)
        }
        val attrBytes = body.toByteArray()
        val length = HEADER_LEN + attrBytes.size
        val packet = ByteArray(length)
        packet[0] = code.toByte()
        packet[1] = identifier.toByte()
        packet[2] = ((length ushr 8) and 0xFF).toByte()
        packet[3] = (length and 0xFF).toByte()
        System.arraycopy(requestAuthenticator, 0, packet, 4, 16)
        System.arraycopy(attrBytes, 0, packet, HEADER_LEN, attrBytes.size)
        val auth = md5(packet, secret)
        System.arraycopy(auth, 0, packet, 4, 16)
        return packet
    }

    /** Atribut Error-Cause (type 101, 4 oktet) untuk balasan NAK. */
    fun errorCause(code: Int): Pair<Int, ByteArray> = ATTR_ERROR_CAUSE to byteArrayOf(
        ((code ushr 24) and 0xFF).toByte(),
        ((code ushr 16) and 0xFF).toByte(),
        ((code ushr 8) and 0xFF).toByte(),
        (code and 0xFF).toByte(),
    )

    private fun md5(vararg parts: Any): ByteArray = MessageDigest.getInstance("MD5").run {
        for (p in parts) when (p) {
            is ByteArray -> update(p)
            is String -> update(p.toByteArray(StandardCharsets.UTF_8))
        }
        digest()
    }
}
