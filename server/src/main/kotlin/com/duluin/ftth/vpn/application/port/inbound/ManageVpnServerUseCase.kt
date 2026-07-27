package com.duluin.ftth.vpn.application.port.inbound

import java.util.UUID

/** Kelola hub OpenVPN — infrastruktur PLATFORM (admin platform): telusur, buat, ubah, kredensial, config, hapus. */
interface ManageVpnServerUseCase {

    fun list(): List<VpnServerView>

    fun get(id: UUID): VpnServerView

    fun create(command: CreateVpnServerCommand): VpnServerView

    fun update(id: UUID, command: UpdateVpnServerCommand): VpnServerView

    /** Set/hapus sertifikat CA & kunci tls-auth. Keduanya null = kosongkan. */
    fun setCredentials(id: UUID, caCertPem: String?, tlsAuthKey: String?): VpnServerView

    /** Rotasi token node hub (mencabut yang lama). View balikan memuat token + perintah pasang baru. */
    fun regenerateNodeToken(id: UUID): VpnServerView

    /** Menolak menghapus hub yang masih punya peer. */
    fun delete(id: UUID)

    /** Render `server.conf` + berkas client-config-dir per peer aktif. */
    fun renderServerConfig(id: UUID): ServerConfigView
}
