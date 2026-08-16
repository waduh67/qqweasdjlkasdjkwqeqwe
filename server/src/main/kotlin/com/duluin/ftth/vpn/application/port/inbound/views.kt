package com.duluin.ftth.vpn.application.port.inbound

import com.duluin.ftth.vpn.domain.model.VpnForwardProtocol
import com.duluin.ftth.vpn.domain.model.VpnProtocol
import java.time.Instant
import java.util.UUID

/**
 * Proyeksi satu hub VPN (infrastruktur PLATFORM) untuk dashboard admin platform.
 * [hasCaCert]/[hasTlsAuth] menandai rahasianya sudah diisi TANPA membocorkan nilainya.
 * [serverAddress] diturunkan dari [tunnelCidr]. [peerCount] = akun terpasang (lintas-tenant).
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
 * Proyeksi satu AKUN VPN milik tenant — semua yang perlu ditempel ke Mikrotik. Endpoint
 * ([host]:[port]/[protocol]) + [securityType] berasal dari hub yang di-auto-assign. [password]
 * SENGAJA hanya terisi sekali saat generate/rotasi (sekali tampil); pada list/get selalu null
 * dan hanya bisa diperoleh ulang lewat unduh config.
 */
data class VpnAccountView(
    val id: UUID,
    val label: String,
    /** Nama hub yang menampung akun ini (untuk tampilan). */
    val serverName: String,
    /** Alamat publik yang di-dial Mikrotik. */
    val host: String,
    val port: Int,
    val protocol: String,
    /** Cipher tunnel (mis. AES-256-GCM). */
    val cipher: String,
    /** Ringkasan tipe keamanan siap-tampil (mis. "OpenVPN (UDP) · AES-256-GCM"). */
    val securityType: String,
    val username: String,
    /** IP overlay tetap yang di-push server — alamat perangkat di dalam tunnel. */
    val overlayIp: String,
    /**
     * Port publik UTAMA (penerusan dengan port terendah — pada akun bawaan itu Winbox).
     * Null bila semua penerusan dicabut: perangkat sengaja tak punya pintu dari internet.
     */
    val remotePort: Int?,
    /** Alamat siap-Winbox: `host:remotePort` — remote perangkat tanpa ikut nge-dial tunnel. */
    val winboxAddress: String?,
    /**
     * Semua penerusan port akun ini (Winbox/SSH/API/…), urut port publik. Inilah yang membuat
     * perangkat ber-port tak-standar tetap terjangkau: sasarannya data, bukan konstanta program.
     */
    val forwards: List<VpnPortForwardView> = emptyList(),
    /**
     * Blok alamat di belakang perangkat yang boleh dituju dari dalam tunnel — kebalikan arah
     * dari [forwards]. Kosong artinya cuma perangkatnya sendiri yang terjangkau; mengisinya
     * itulah yang membuat server sanggup menghubungi ONT pelanggan tanpa menunggu ONT menyapa.
     */
    val routes: List<VpnRoutedSubnetView> = emptyList(),
    /** Status ADMINISTRATIF akun (ENABLED/DISABLED) — apakah boleh terhubung. */
    val status: String,
    /** Liveness NYATA: true selagi hub melapor perangkat terhubung (callback OpenVPN). */
    val online: Boolean = false,
    /** Waktu perangkat terakhir dilaporkan terhubung; null bila hub belum pernah melapor. */
    val lastHandshakeAt: Instant?,
    /**
     * True bila hub-nya TCP → juga melayani perangkat **RouterOS v6** (TCP + AES-256-CBC), bukan
     * cuma v7. Menentukan apakah UI menawarkan varian/unduhan v6.
     */
    val supportsV6: Boolean = false,
    /** Sekali tampil saat generate/rotasi; null pada list/get. */
    val password: String? = null,
    /**
     * Perintah RouterOS v7 satu-baris siap salin-tempel di terminal Mikrotik (sudah berisi
     * [password]). Sekali tampil bersama [password]; null pada list/get.
     */
    val routerOsCommand: String? = null,
    /**
     * Perintah RouterOS **v6** satu-baris (TCP + AES-256-CBC, sintaks menu lama) — best-effort untuk
     * perangkat lama. Sekali tampil bersama [password] DAN hanya bila [supportsV6]; null selain itu.
     */
    val routerOsCommandV6: String? = null,
)

/**
 * Satu penerusan port akun: hub mendengarkan [publicPort] dan meneruskannya ke [devicePort]
 * pada perangkat. [address] sudah dirakit siap tempel di Winbox/PuTTY, [label] sekadar nama
 * layanan untuk manusia.
 */
data class VpnPortForwardView(
    val id: UUID,
    val label: String,
    val publicPort: Int,
    val devicePort: Int,
    val protocol: String,
    val address: String,
)

/**
 * Satu blok alamat di belakang akun. [cidr] sudah dinormalisasi ke alamat network, jadi operator
 * yang menempel alamat pelanggan (`10.20.255.254/16`) tetap melihat blok yang benar setelah
 * disimpan. [label] sekadar nama untuk manusia — satu BRAS lazim membawa beberapa kolam.
 */
data class VpnRoutedSubnetView(
    val id: UUID,
    val label: String,
    val cidr: String,
)

/**
 * Konfigurasi server OpenVPN siap-pakai: [serverConf] adalah isi `server.conf`, dan [ccd]
 * memetakan username peer → baris client-config-dir (`ifconfig-push ...`) yang mengunci IP
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

/**
 * Generate akun VPN untuk tenant: hub dipilih otomatis (auto-assign). [label] kosong =
 * diberi nama default. [username] null/blank = diturunkan dari label & dijamin unik per hub.
 */
data class GenerateVpnAccountCommand(
    val label: String?,
    val deviceType: String?,
    val deviceId: UUID?,
    val username: String?,
)

/**
 * Tambah/ubah penerusan port. [devicePort] adalah port layanan DI PERANGKAT (yang berubah
 * ketika operator memindah port Winbox/API-nya); port publiknya dialokasikan sistem dan tak
 * pernah ditentukan pemanggil. [label] kosong = diturunkan dari port yang umum dikenal.
 * [protocol] null = TCP (nyaris semua layanan manajemen).
 */
data class VpnPortForwardCommand(
    val label: String?,
    val devicePort: Int,
    val protocol: VpnForwardProtocol?,
)

/**
 * Daftarkan/ubah blok di belakang akun. [cidr] boleh berisi alamat host apa pun di dalam blok —
 * dinormalisasi ke alamat network, sebab yang paling sering ada di tangan operator adalah alamat
 * satu pelanggan, bukan blok kolamnya. [label] kosong = diberi nama default.
 */
data class VpnRouteCommand(
    val label: String?,
    val cidr: String,
)

/**
 * Ganti nama satu blok. Bloknya sendiri sengaja tak bisa diubah: mengubahnya sama saja mencabut
 * rute lama dan memasang yang baru, dan memisahkannya membuat operator sadar bahwa jalur lama
 * benar-benar hilang — bukan diam-diam berpindah.
 */
data class VpnRouteLabelCommand(
    val label: String?,
)
