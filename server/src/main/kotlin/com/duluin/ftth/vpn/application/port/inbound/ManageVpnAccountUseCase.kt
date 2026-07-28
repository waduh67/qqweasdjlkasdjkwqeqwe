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

    /** Berkas `.ovpn` klien generik (berisi kredensial); [variant] memilih cipher v7 (GCM)/v6 (CBC). */
    fun renderOvpn(id: UUID, variant: VpnClientVariant = VpnClientVariant.V7): String

    /**
     * Skrip RouterOS (ovpn-client) untuk akun ini. [variant] V7 (default; UDP/TCP + GCM) atau
     * V6 (TCP + CBC, sintaks menu lama). V6 pada hub non-TCP ditolak (v6 mustahil dial UDP).
     */
    fun renderRouterOs(id: UUID, variant: VpnClientVariant = VpnClientVariant.V7): String
}
