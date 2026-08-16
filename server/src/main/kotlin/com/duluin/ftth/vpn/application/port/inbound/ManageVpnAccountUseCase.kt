package com.duluin.ftth.vpn.application.port.inbound

import com.duluin.ftth.vpn.domain.model.VpnClientVariant
import java.util.UUID

/**
 * Kelola AKUN VPN milik tenant: telusur, generate (auto-assign hub), enable/disable, rotasi,
 * unduh config. Tenant tak pernah memilih/melihat hub — cukup men-generate akun.
 */
interface ManageVpnAccountUseCase {

    /** Akun milik tenant aktif. */
    fun list(): List<VpnAccountView>

    fun get(id: UUID): VpnAccountView

    /**
     * Generate akun baru: sistem memilih hub yang tersedia (auto-assign), mengalokasikan IP
     * overlay & username unik per hub, lalu membuat kredensial. View balikan memuat password
     * (sekali tampil).
     */
    fun generate(command: GenerateVpnAccountCommand): VpnAccountView

    fun enable(id: UUID): VpnAccountView

    fun disable(id: UUID): VpnAccountView

    /** Ganti password akun; password baru hanya keluar lewat view balikan / unduh config. */
    fun rotatePassword(id: UUID): VpnAccountView

    fun delete(id: UUID)

    /**
     * Buka satu pintu lagi ke perangkat (mis. SSH di samping Winbox): sistem mengalokasikan port
     * publik berikutnya di hub, pemanggil cukup menyebut port layanan di perangkatnya.
     */
    fun addForward(id: UUID, command: VpnPortForwardCommand): VpnAccountView

    /**
     * Arahkan ulang satu penerusan — inilah jalan keluar ketika port Winbox/API di perangkat
     * dipindah. Port publiknya sengaja TETAP supaya alamat yang sudah dipegang teknisi tak basi.
     */
    fun retargetForward(id: UUID, forwardId: UUID, command: VpnPortForwardCommand): VpnAccountView

    /** Cabut satu penerusan; portnya kembali ke kolam hub. Akun tanpa penerusan tetap sah. */
    fun removeForward(id: UUID, forwardId: UUID): VpnAccountView

    /**
     * Akui satu blok alamat sebagai penghuni belakang perangkat ini (mis. kolam PPPoE pelanggan).
     * Sejak itu server bisa MENGHUBUNGI isi blok tersebut — yang membuat perintah TR-069 ke ONT
     * jalan seketika alih-alih mengantre sampai perangkatnya kebetulan menyapa.
     */
    fun addRoute(id: UUID, command: VpnRouteCommand): VpnAccountView

    /** Ganti nama satu blok; bloknya sendiri tak bisa diubah (cabut lalu daftarkan yang baru). */
    fun renameRoute(id: UUID, routeId: UUID, command: VpnRouteLabelCommand): VpnAccountView

    /** Cabut satu blok: hub berhenti merutekannya. Perangkat di dalamnya kembali tak terjangkau. */
    fun removeRoute(id: UUID, routeId: UUID): VpnAccountView

    /** Berkas `.ovpn` klien generik (berisi kredensial); [variant] memilih cipher v7 (GCM)/v6 (CBC). */
    fun renderOvpn(id: UUID, variant: VpnClientVariant = VpnClientVariant.V7): String

    /**
     * Skrip RouterOS (ovpn-client) untuk akun ini. [variant] V7 (default; UDP/TCP + GCM) atau
     * V6 (TCP + CBC, sintaks menu lama). V6 pada hub non-TCP ditolak (v6 mustahil dial UDP).
     */
    fun renderRouterOs(id: UUID, variant: VpnClientVariant = VpnClientVariant.V7): String
}
