package com.duluin.ftth.vpn.application.port.inbound

import com.duluin.ftth.vpn.domain.model.VpnProtocol
import java.time.Instant
import java.util.UUID

/**
 * Proyeksi satu hub VPN untuk UI. [hasCaCert]/[hasTlsAuth] menandai rahasianya sudah
 * diisi TANPA pernah membocorkan nilainya — sertifikat/kunci hanya keluar lewat endpoint
 * unduh config yang berizin terpisah. [serverAddress] diturunkan dari [tunnelCidr].
 */
data class VpnServerView(
    val id: UUID,
    val name: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val tunnelCidr: String,
    val serverAddress: String,
    val status: String,
    val hasCaCert: Boolean,
    val hasTlsAuth: Boolean,
    /** True bila aplikasi sudah menerbitkan CA + sertifikat server → hub siap dipasang. */
    val pkiReady: Boolean,
    val peerCount: Long,
    /**
     * Token node MENTAH — hanya terisi tepat setelah hub dibuat / token dirotasi (sekali tampil,
     * tak pernah bisa dibaca ulang), null pada list/get. Kredensial installer + callback VPS.
     */
    val nodeToken: String? = null,
    /** Perintah pasang satu-baris siap tempel di VPS; terisi bersama [nodeToken]. */
    val installCommand: String? = null,
)

/**
 * Proyeksi satu peer/perangkat VPN. Password SENGAJA tidak disertakan — hanya bisa
 * dirotasi, tak pernah dibaca balik lewat pandangan biasa (hanya lewat unduh config).
 */
data class VpnPeerView(
    val id: UUID,
    val serverId: UUID,
    val name: String,
    val username: String,
    val overlayIp: String,
    val status: String,
    val deviceType: String?,
    val deviceId: UUID?,
    val lastHandshakeAt: Instant?,
)

/**
 * Konfigurasi server OpenVPN siap-pakai: [serverConf] adalah isi `server.conf`, dan [ccd]
 * memetakan username peter → baris client-config-dir (`ifconfig-push ...`) yang mengunci IP
 * overlay tiap peer aktif.
 */
data class ServerConfigView(
    val serverConf: String,
    val ccd: Map<String, String>,
)

/** Field opsional (port/protocol/tunnelCidr) di-default dari VpnProperties bila null. */
data class CreateVpnServerCommand(
    val name: String,
    val host: String,
    val port: Int?,
    val protocol: VpnProtocol?,
    val tunnelCidr: String?,
)

/** Ubah nama & titik dial hub; subnet dan kredensial tak disentuh dari sini. */
data class UpdateVpnServerCommand(
    val name: String,
    val host: String,
    val port: Int,
    val protocol: VpnProtocol,
)

/** [username] null/blank = diturunkan otomatis dari [name] dan dijamin unik per server. */
data class CreateVpnPeerCommand(
    val name: String,
    val deviceType: String?,
    val deviceId: UUID?,
    val username: String?,
)
