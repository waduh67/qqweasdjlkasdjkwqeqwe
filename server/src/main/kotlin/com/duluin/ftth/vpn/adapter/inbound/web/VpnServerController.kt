package com.duluin.ftth.vpn.adapter.inbound.web

import com.duluin.ftth.vpn.application.port.inbound.CreateVpnServerCommand
import com.duluin.ftth.vpn.application.port.inbound.ManageVpnServerUseCase
import com.duluin.ftth.vpn.application.port.inbound.ServerConfigView
import com.duluin.ftth.vpn.application.port.inbound.UpdateVpnServerCommand
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
 * Kelola HUB VPN — infrastruktur PLATFORM (OpenVPN server jalan di VPS kita, IP publik kita).
 * Semua endpoint berizin `vpn.server.*` yang platform-only, jadi hanya admin platform yang
 * menyentuh hub. Tenant tak pernah ke sini; mereka cukup generate akun (lihat
 * [VpnAccountController]). Config berisi kredensial → endpointnya berizin terpisah
 * (`vpn.config.view`). Aturan nilai ditegakkan di domain.
 */
@RestController
@RequestMapping("/api/vpn/servers")
@Tag(name = "VPN — hub platform (admin platform)")
@SecurityRequirement(name = "bearer-jwt")
class VpnServerController(
    private val servers: ManageVpnServerUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('vpn.server.view')")
    @Operation(summary = "Daftar hub VPN platform")
    fun list(): List<VpnServerView> = servers.list()

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('vpn.server.view')")
    @Operation(summary = "Detail satu hub VPN")
    fun get(@PathVariable id: UUID): VpnServerView = servers.get(id)

    @PostMapping
    @PreAuthorize("@authz.can('vpn.server.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Daftarkan hub VPN baru (app terbitkan CA + cert server, kembalikan perintah pasang)")
    fun create(@Valid @RequestBody request: CreateVpnServerRequest): VpnServerView =
        servers.create(request.toCommand())

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('vpn.server.manage')")
    @Operation(summary = "Ubah nama & titik dial hub VPN")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateVpnServerRequest): VpnServerView =
        servers.update(id, request.toCommand())

    @PutMapping("/{id}/credentials")
    @PreAuthorize("@authz.can('vpn.server.manage')")
    @Operation(summary = "Set/hapus sertifikat CA & kunci tls-auth hub")
    fun setCredentials(@PathVariable id: UUID, @RequestBody request: SetCredentialsRequest): VpnServerView =
        servers.setCredentials(id, request.caCertPem, request.tlsAuthKey)

    @PostMapping("/{id}/regenerate-token")
    @PreAuthorize("@authz.can('vpn.server.manage')")
    @Operation(summary = "Rotasi token node hub (kembalikan token + perintah pasang baru, sekali tampil)")
    fun regenerateToken(@PathVariable id: UUID): VpnServerView = servers.regenerateNodeToken(id)

    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.can('vpn.server.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Hapus hub VPN (ditolak bila masih menampung akun)")
    fun delete(@PathVariable id: UUID) = servers.delete(id)

    @GetMapping("/{id}/config")
    @PreAuthorize("@authz.can('vpn.config.view')")
    @Operation(summary = "Unduh server.conf + client-config-dir per akun aktif")
    fun serverConfig(@PathVariable id: UUID): ServerConfigView = servers.renderServerConfig(id)
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
    /** Bawaan TCP: itu satu-satunya protokol yang dimengerti klien OpenVPN RouterOS v6. */
    val protocol: VpnProtocol = VpnProtocol.TCP,
) {
    fun toCommand() = UpdateVpnServerCommand(name, host, port, protocol)
}

/** Kedua field null = kosongkan kredensial hub. */
data class SetCredentialsRequest(
    val caCertPem: String?,
    val tlsAuthKey: String?,
)
