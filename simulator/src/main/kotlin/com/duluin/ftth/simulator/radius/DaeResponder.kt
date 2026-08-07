package com.duluin.ftth.simulator.radius

import com.duluin.ftth.contract.radius.RadiusDae
import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Responder DAE (RFC 5176) sisi-NAS: mendengarkan UDP dan menjawab Disconnect/CoA yang server
 * tembak. Ini bagian yang menutup celah lab — tanpa sesuatu yang hidup di `:3799`, aksi isolir
 * & Reset Login berujung FAILED.
 *
 * Menolak diam-diam paket dengan Request Authenticator salah (secret tak cocok), seperti BRAS
 * sungguhan. Balasan ditandatangani dengan Response Authenticator yang benar lewat [DaeCodec]
 * sehingga sisi server ([RadiusDae.verifyResponse]) menerimanya. Keputusan ACK/NAK didelegasikan
 * ke [NasSessionControl].
 */
class DaeResponder(
    private val bindAddress: String,
    private val port: Int,
    private val secret: String,
    private val control: NasSessionControl,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start() {
        val s = DatagramSocket(InetSocketAddress(bindAddress, port))
        socket = s
        running = true
        thread = Thread({ loop(s) }, "sim-dae-responder").apply {
            isDaemon = true
            start()
        }
        log.info("Responder DAE mendengarkan di {}/{} (UDP)", bindAddress, port)
    }

    fun stop() {
        running = false
        socket?.close() // membuka blokir receive() → loop keluar
        socket = null
    }

    private fun loop(s: DatagramSocket) {
        val buffer = ByteArray(4096)
        while (running) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(datagram)
            } catch (e: Exception) {
                if (running) log.debug("Soket DAE terganggu", e)
                break
            }
            runCatching { handle(s, datagram) }
                .onFailure { log.warn("Gagal memproses paket DAE", it) }
        }
    }

    private fun handle(s: DatagramSocket, datagram: DatagramPacket) {
        val packet = datagram.data.copyOf(datagram.length)
        if (packet.size < 20) return
        if (!DaeCodec.verifyRequest(packet, secret)) {
            log.debug("Paket DAE ditolak: secret salah (authenticator tak cocok)")
            return
        }
        val attrs = DaeCodec.attributes(packet)
        val username = DaeCodec.stringAttr(attrs, RadiusDae.ATTR_USER_NAME) ?: return
        val sessionId = DaeCodec.stringAttr(attrs, RadiusDae.ATTR_ACCT_SESSION_ID)
        val identifier = DaeCodec.identifier(packet)
        val requestAuth = DaeCodec.authenticator(packet)

        val (responseCode, errorCause) = when (DaeCodec.code(packet)) {
            RadiusDae.DISCONNECT_REQUEST ->
                if (control.disconnect(username, sessionId)) RadiusDae.DISCONNECT_ACK to null
                else RadiusDae.DISCONNECT_NAK to SESSION_NOT_FOUND
            RadiusDae.COA_REQUEST ->
                if (control.changeRate(username, sessionId)) RadiusDae.COA_ACK to null
                else RadiusDae.COA_NAK to SESSION_NOT_FOUND
            else -> return // tipe lain diabaikan
        }

        val responseAttrs = errorCause?.let { listOf(DaeCodec.errorCause(it)) } ?: emptyList()
        val response = DaeCodec.buildResponse(responseCode, identifier, requestAuth, secret, responseAttrs)
        s.send(DatagramPacket(response, response.size, datagram.address, datagram.port))
    }

    companion object {
        /** RFC 5176 §3.5 — sesi yang diminta tak ditemukan. */
        private const val SESSION_NOT_FOUND = 503
    }
}
