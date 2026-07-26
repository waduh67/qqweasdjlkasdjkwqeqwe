package com.duluin.ftth.collector.adapter

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * BRAS/NAS tiruan sisi-UDP untuk menguji jalur DAE tanpa perangkat: menerima
 * Disconnect/CoA-Request, membalas ACK/NAK dengan Response Authenticator yang benar
 * (atau sengaja salah, untuk menguji penolakan), dan menyimpan paket permintaan agar
 * atribut yang dikirim adapter bisa diperiksa.
 */
class RadiusNasStub(
    private val secret: String,
    /** Balasan per kode permintaan; null = diam (menguji timeout/retransmit). */
    private val reply: (requestCode: Int) -> Reply?,
) : AutoCloseable {

    data class Reply(val code: Int, val errorCause: Int? = null)

    private val socket = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
    private val lastRequest = AtomicReference<ByteArray?>()
    private val count = AtomicInteger()
    private val thread = Thread { loop() }

    val port: Int get() = socket.localPort
    val received: ByteArray? get() = lastRequest.get()
    val requestCount: Int get() = count.get()

    fun start() = apply { thread.isDaemon = true; thread.start() }

    override fun close() {
        socket.close()
        thread.interrupt()
    }

    private fun loop() {
        val buffer = ByteArray(4096)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                return // socket ditutup di akhir tes
            }
            val request = buffer.copyOf(packet.length)
            lastRequest.set(request)
            count.incrementAndGet()
            val answer = reply(request[0].toInt() and 0xFF) ?: continue
            val id = request[1].toInt() and 0xFF
            val requestAuth = request.copyOfRange(4, 20)
            val response = buildResponse(answer.code, id, requestAuth, answer.errorCause)
            socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
        }
    }

    private fun buildResponse(code: Int, id: Int, requestAuth: ByteArray, errorCause: Int?): ByteArray {
        val attrs = ByteArrayOutputStream()
        if (errorCause != null) {
            attrs.write(101)
            attrs.write(6)
            attrs.write((errorCause ushr 24) and 0xFF)
            attrs.write((errorCause ushr 16) and 0xFF)
            attrs.write((errorCause ushr 8) and 0xFF)
            attrs.write(errorCause and 0xFF)
        }
        val body = attrs.toByteArray()
        val length = 20 + body.size
        val packet = ByteArray(length)
        packet[0] = code.toByte()
        packet[1] = id.toByte()
        packet[2] = ((length ushr 8) and 0xFF).toByte()
        packet[3] = (length and 0xFF).toByte()
        System.arraycopy(requestAuth, 0, packet, 4, 16) // Response Auth dihitung atas Request Auth
        System.arraycopy(body, 0, packet, 20, body.size)
        val auth = MessageDigest.getInstance("MD5").run {
            update(packet)
            update(secret.toByteArray(StandardCharsets.UTF_8))
            digest()
        }
        System.arraycopy(auth, 0, packet, 4, 16)
        return packet
    }

    companion object {
        /** Memecah atribut RADIUS sebuah paket menjadi peta type → daftar nilai mentah. */
        fun attributes(packet: ByteArray): Map<Int, List<ByteArray>> {
            val out = HashMap<Int, MutableList<ByteArray>>()
            var i = 20
            while (i + 2 <= packet.size) {
                val type = packet[i].toInt() and 0xFF
                val len = packet[i + 1].toInt() and 0xFF
                if (len < 2 || i + len > packet.size) break
                out.getOrPut(type) { mutableListOf() }.add(packet.copyOfRange(i + 2, i + len))
                i += len
            }
            return out
        }

        fun stringAttr(packet: ByteArray, type: Int): String? =
            attributes(packet)[type]?.firstOrNull()?.toString(StandardCharsets.UTF_8)
    }
}
