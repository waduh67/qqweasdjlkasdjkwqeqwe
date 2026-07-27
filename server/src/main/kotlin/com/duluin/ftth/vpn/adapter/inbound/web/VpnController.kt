package com.duluin.ftth.vpn.adapter.inbound.web

import com.duluin.ftth.vpn.application.port.inbound.CreateVpnPeerCommand
import com.duluin.ftth.vpn.application.port.inbound.CreateVpnServerCommand
import com.duluin.ftth.vpn.application.port.inbound.ManageVpnPeerUseCase
import com.duluin.ftth.vpn.application.port.inbound.ManageVpnServerUseCase
import com.duluin.ftth.vpn.application.port.inbound.ServerConfigView
import com.duluin.ftth.vpn.application.port.inbound.UpdateVpnServerCommand
import com.duluin.ftth.vpn.application.port.inbound.VpnPeerView
import com.duluin.ftth.vpn.application.port.inbound.VpnServerView
import com.duluin.ftth.vpn.domain.model.VpnProtocol
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * VPN manajemen (back-haul OpenVPN): kelola hub, peer/perangkat yang men-dial masuk, dan
 * unduh berkas konfigurasi. Config berisi kredensial — endpointnya berizin terpisah
 * (`vpn.config.view`). Aturan nilai ditegakkan di domain; controller hanya menautkan izin
 * dan bentuk request/response.
 */
@RestController
@RequestMapping("/api/vpn")
@Tag(name = "VPN — back-haul OpenVPN manajemen perangkat")
@SecurityRequirement(name = "bearer-jwt")
class VpnController(
    private val servers: ManageVpnServerUseCase,
    private val peers: ManageVpnPeerUseCase,
) {
    // ---- Hub VPN ----

    @GetMapping("/servers")
    @PreAuthorize("@authz.can('vpn.server.view')")
    @Operation(summary = "Daftar hub VPN tenant")
    fun listServers(): List<VpnServerView> = servers.list()

    @GetMapping("/servers/{id}")
    @PreAuthorize("@authz.can('vpn.server.view')")
    @Operation(summary = "Detail satu hub VPN")
    fun getServer(@PathVariable id: UUID): VpnServerView = servers.get(id)

    @PostMapping("/servers")
    @PreAuthorize("@authz.can('vpn.server.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Daftarkan hub VPN baru")
    fun createServer(@Valid @RequestBody request: CreateVpnServerRequest): VpnServerView =
        servers.create(request.toCommand())

    @PutMapping("/servers/{id}")
    @PreAuthorize("@authz.can('vpn.server.manage')")
    @Operation(summary = "Ubah nama & titik dial hub VPN")
    fun updateServer(@PathVariable id: UUID, @Valid @RequestBody request: UpdateVpnServerRequest): VpnServerView =
        servers.update(id, request.toCommand())

    @PutMapping("/servers/{id}/credentials")
    @PreAuthorize("@authz.can('vpn.server.manage')")
    @Operation(summary = "Set/hapus sertifikat CA & kunci tls-auth hub")
    fun setCredentials(@PathVariable id: UUID, @RequestBody request: SetCredentialsRequest): VpnServerView =
        servers.setCredentials(id, request.caCertPem, request.tlsAuthKey)

    @PostMapping("/servers/{id}/regenerate-token")
    @PreAuthorize("@authz.can('vpn.server.manage')")
    @Operation(summary = "Rotasi token node hub (kembalikan token + perintah pasang baru, sekali tampil)")
    fun regenerateToken(@PathVariable id: UUID): VpnServerView = servers.regenerateNodeToken(id)

    @DeleteMapping("/servers/{id}")
    @PreAuthorize("@authz.can('vpn.server.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Hapus hub VPN (ditolak bila masih punya peer)")
    fun deleteServer(@PathVariable id: UUID) = servers.delete(id)

    @GetMapping("/servers/{id}/config")
    @PreAuthorize("@authz.can('vpn.config.view')")
    @Operation(summary = "Unduh server.conf + client-config-dir per peer aktif")
    fun serverConfig(@PathVariable id: UUID): ServerConfigView = servers.renderServerConfig(id)

    // ---- Peer/perangkat ----

    @GetMapping("/servers/{id}/peers")
    @PreAuthorize("@authz.can('vpn.peer.view')")
    @Operation(summary = "Daftar peer sebuah hub")
    fun listPeers(@PathVariable id: UUID): List<VpnPeerView> = peers.listByServer(id)

    @PostMapping("/servers/{id}/peers")
    @PreAuthorize("@authz.can('vpn.peer.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tambah peer (IP overlay & password digenerate otomatis)")
    fun createPeer(@PathVariable id: UUID, @Valid @RequestBody request: CreateVpnPeerRequest): VpnPeerView =
        peers.create(id, request.toCommand())

    @GetMapping("/peers/{id}")
    @PreAuthorize("@authz.can('vpn.peer.view')")
    @Operation(summary = "Detail satu peer")
    fun getPeer(@PathVariable id: UUID): VpnPeerView = peers.get(id)

    @PostMapping("/peers/{id}/enable")
    @PreAuthorize("@authz.can('vpn.peer.manage')")
    @Operation(summary = "Aktifkan peer")
    fun enablePeer(@PathVariable id: UUID): VpnPeerView = peers.enable(id)

    @PostMapping("/peers/{id}/disable")
    @PreAuthorize("@authz.can('vpn.peer.manage')")
    @Operation(summary = "Nonaktifkan peer")
    fun disablePeer(@PathVariable id: UUID): VpnPeerView = peers.disable(id)

    @PostMapping("/peers/{id}/rotate-password")
    @PreAuthorize("@authz.can('vpn.peer.manage')")
    @Operation(summary = "Rotasi password peer")
    fun rotatePassword(@PathVariable id: UUID): VpnPeerView = peers.rotatePassword(id)

    @DeleteMapping("/peers/{id}")
    @PreAuthorize("@authz.can('vpn.peer.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Hapus peer")
    fun deletePeer(@PathVariable id: UUID) = peers.delete(id)

    @GetMapping("/peers/{id}/ovpn", produces = ["text/plain"])
    @PreAuthorize("@authz.can('vpn.config.view')")
    @Operation(summary = "Unduh berkas .ovpn peer (berisi kredensial)")
    fun peerOvpn(@PathVariable id: UUID): String = peers.renderOvpn(id)

    @GetMapping("/peers/{id}/routeros", produces = ["text/plain"])
    @PreAuthorize("@authz.can('vpn.config.view')")
    @Operation(summary = "Unduh skrip RouterOS peer (berisi kredensial)")
    fun peerRouterOs(@PathVariable id: UUID): String = peers.renderRouterOs(id)
}

data class CreateVpnServerRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val host: String,
    val port: Int? = null,
    val protocol: VpnProtocol? = null,
    val tunnelCidr: String? = null,
) {
    fun toCommand() = CreateVpnServerCommand(name, host, port, protocol, tunnelCidr)
}

data class UpdateVpnServerRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val host: String,
    val port: Int = 1194,
    val protocol: VpnProtocol = VpnProtocol.UDP,
) {
    fun toCommand() = UpdateVpnServerCommand(name, host, port, protocol)
}

/** Kedua field null = kosongkan kredensial hub. */
data class SetCredentialsRequest(
    val caCertPem: String?,
    val tlsAuthKey: String?,
)

data class CreateVpnPeerRequest(
    @field:NotBlank val name: String,
    val deviceType: String? = null,
    val deviceId: UUID? = null,
    /** Kosong = username diturunkan dari nama & dijamin unik per server. */
    val username: String? = null,
) {
    fun toCommand() = CreateVpnPeerCommand(name, deviceType, deviceId, username)
}
