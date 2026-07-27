package com.duluin.ftth.vpn.application.port.inbound

import java.util.UUID

/** Kelola peer/perangkat yang men-dial hub: telusur, buat, enable/disable, rotasi, config. */
interface ManageVpnPeerUseCase {

    fun listByServer(serverId: UUID): List<VpnPeerView>

    fun get(id: UUID): VpnPeerView

    /**
     * Buat peer baru: IP overlay dialokasikan otomatis dari subnet server, username
     * diturunkan & dijamin unik bila kosong, password digenerate.
     */
    fun create(serverId: UUID, command: CreateVpnPeerCommand): VpnPeerView

    fun enable(id: UUID): VpnPeerView

    fun disable(id: UUID): VpnPeerView

    /** Ganti password peer dengan yang baru digenerate. */
    fun rotatePassword(id: UUID): VpnPeerView

    fun delete(id: UUID)

    /** Berkas `.ovpn` klien generik (berisi kredensial) untuk peer ini. */
    fun renderOvpn(id: UUID): String

    /** Skrip RouterOS v7 (ovpn-client) untuk peer ini. */
    fun renderRouterOs(id: UUID): String
}
