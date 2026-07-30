package com.duluin.ftth.bng.adapter.outbound.radius

import com.duluin.ftth.bng.application.port.outbound.RadiusSessionControlPort
import com.duluin.ftth.contract.radius.DaeResult
import com.duluin.ftth.contract.radius.RadiusDae
import com.duluin.ftth.contract.radius.RadiusDaeClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Mengirim DAE (RFC 5176) LANGSUNG dari server ke BRAS/NAS untuk mengontrol sesi hidup —
 * jalur server-side dari kontrol yang dulu hanya lewat collector on-prem. Dipakai untuk NAS
 * yang server jangkau sendiri — reachability DIRECT (IP publik → tembak `:3799`) atau VPN
 * (IP overlay lewat tunnel; server ko-lokasi hub VPN). Paket dirakit dengan codec bersama [RadiusDae] di modul `contract`
 * — satu implementasi, dipakai collector maupun server, tak ada dua salinan yang bisa menyimpang.
 *
 * Keputusan idempotensi sama seperti adapter FreeRADIUS collector: Disconnect yang dibalas
 * NAK 503 (sesi sudah hilang) dianggap SELESAI, bukan gagal — target tercapai. CoA yang di-NAK
 * dilempar (pemanggil memutuskan degradasi). [transport] adalah jahitan uji: default menembak
 * UDP sungguhan lewat [RadiusDaeClient], test menyuntik transport palsu tanpa soket.
 */
@Component
class ServerRadiusDaeAdapter(
    private val transport: DaeTransport = DaeTransport(RadiusDaeClient()::send),
    private val daePort: Int = RadiusDae.DEFAULT_PORT,
) : RadiusSessionControlPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun disconnect(
        host: String,
        secret: String,
        username: String,
        acctSessionId: String?,
        nasIp: String?,
        identifier: Int,
    ) {
        val attributes = buildList {
            add(RadiusDae.userName(username))
            acctSessionId?.let { add(RadiusDae.acctSessionId(it)) }
            RadiusDae.nasIpAddress(nasIp ?: host)?.let(::add)
        }
        val result = transport.send(host, daePort, secret, RadiusDae.DISCONNECT_REQUEST, identifier, attributes)
        when (result.code) {
            RadiusDae.DISCONNECT_ACK ->
                log.info("DAE server: memutus sesi {} di BRAS {}", username, host)
            // Sesi hilang antara baca radacct dan DAE — sudah tercapai, jangan gagalkan.
            RadiusDae.DISCONNECT_NAK -> if (result.errorCause == SESSION_NOT_FOUND) {
                log.info("DAE server: sesi {} sudah tak ada di BRAS {} — DISCONNECT selesai", username, host)
            } else {
                throw IllegalStateException(
                    "Disconnect $username ditolak BRAS $host: ${RadiusDae.errorCauseLabel(result.errorCause)}",
                )
            }
            else -> throw IllegalStateException("Balasan DAE tak terduga (kode ${result.code}) dari $host")
        }
    }

    override fun changeRate(
        host: String,
        secret: String,
        username: String,
        downMbps: Int,
        upMbps: Int,
        acctSessionId: String?,
        identifier: Int,
    ) {
        val attributes = buildList {
            add(RadiusDae.userName(username))
            acctSessionId?.let { add(RadiusDae.acctSessionId(it)) }
            add(RadiusDae.mikrotikRateLimit(upMbps, downMbps))
        }
        val result = transport.send(host, daePort, secret, RadiusDae.COA_REQUEST, identifier, attributes)
        when (result.code) {
            RadiusDae.COA_ACK ->
                log.info("DAE server: CoA {} → {}/{} Mbps di BRAS {}", username, downMbps, upMbps, host)
            RadiusDae.COA_NAK -> throw IllegalStateException(
                "CoA $username ditolak BRAS $host: ${RadiusDae.errorCauseLabel(result.errorCause)}",
            )
            else -> throw IllegalStateException("Balasan DAE tak terduga (kode ${result.code}) dari $host")
        }
    }

    companion object {
        private const val SESSION_NOT_FOUND = 503
    }
}

/**
 * Jahitan uji untuk pengiriman DAE. [RadiusDaeClient] di `contract` bersifat `final` (modul
 * itu tanpa all-open Kotlin), jadi kita tak bisa men-subclass-nya — cukup fungsi kirim yang
 * bisa diganti transport palsu di test tanpa menembak soket UDP sungguhan.
 */
fun interface DaeTransport {
    @Suppress("LongParameterList")
    fun send(
        host: String,
        port: Int,
        secret: String,
        code: Int,
        identifier: Int,
        attributes: List<RadiusDae.Attribute>,
    ): DaeResult
}
