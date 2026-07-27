package com.duluin.ftth.vpn.application.port.inbound

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

    /** Berkas `.ovpn` klien generik (berisi kredensial) untuk akun ini. */
    fun renderOvpn(id: UUID): String

    /** Skrip RouterOS v7 (ovpn-client) untuk akun ini. */
    fun renderRouterOs(id: UUID): String
}
