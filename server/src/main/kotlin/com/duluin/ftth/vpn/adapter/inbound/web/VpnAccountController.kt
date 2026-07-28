package com.duluin.ftth.vpn.adapter.inbound.web

import com.duluin.ftth.vpn.application.port.inbound.GenerateVpnAccountCommand
import com.duluin.ftth.vpn.application.port.inbound.ManageVpnAccountUseCase
import com.duluin.ftth.vpn.application.port.inbound.VpnAccountView
import com.duluin.ftth.vpn.domain.model.VpnClientVariant
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * AKUN VPN milik tenant. Alur intinya satu klik: [generate] → sistem auto-assign ke hub platform,
 * balikan memuat endpoint + kredensial siap tempel ke Mikrotik (password sekali tampil). Tenant
 * tak pernah menyentuh/melihat hub. Unduh config berisi kredensial → izin terpisah
 * (`vpn.config.view`).
 */
@RestController
@RequestMapping("/api/vpn/accounts")
@Tag(name = "VPN — akun tenant")
@SecurityRequirement(name = "bearer-jwt")
class VpnAccountController(
    private val accounts: ManageVpnAccountUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('vpn.peer.view')")
    @Operation(summary = "Daftar akun VPN tenant")
    fun list(): List<VpnAccountView> = accounts.list()

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('vpn.peer.view')")
    @Operation(summary = "Detail satu akun VPN")
    fun get(@PathVariable id: UUID): VpnAccountView = accounts.get(id)

    @PostMapping("/generate")
    @PreAuthorize("@authz.can('vpn.peer.manage')")
    @Operation(summary = "Generate akun VPN baru (auto-assign hub; kredensial + password sekali tampil)")
    fun generate(@RequestBody(required = false) request: GenerateVpnAccountRequest?): VpnAccountView =
        accounts.generate((request ?: GenerateVpnAccountRequest()).toCommand())

    @PostMapping("/{id}/enable")
    @PreAuthorize("@authz.can('vpn.peer.manage')")
    @Operation(summary = "Aktifkan akun VPN")
    fun enable(@PathVariable id: UUID): VpnAccountView = accounts.enable(id)

    @PostMapping("/{id}/disable")
    @PreAuthorize("@authz.can('vpn.peer.manage')")
    @Operation(summary = "Nonaktifkan akun VPN")
    fun disable(@PathVariable id: UUID): VpnAccountView = accounts.disable(id)

    @PostMapping("/{id}/rotate-password")
    @PreAuthorize("@authz.can('vpn.peer.manage')")
    @Operation(summary = "Rotasi password akun (password baru sekali tampil)")
    fun rotatePassword(@PathVariable id: UUID): VpnAccountView = accounts.rotatePassword(id)

    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.can('vpn.peer.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Hapus akun VPN")
    fun delete(@PathVariable id: UUID) = accounts.delete(id)

    @GetMapping("/{id}/ovpn", produces = ["text/plain"])
    @PreAuthorize("@authz.can('vpn.config.view')")
    @Operation(summary = "Unduh berkas .ovpn akun (berisi kredensial); variant V7 (GCM) / V6 (CBC)")
    fun ovpn(
        @PathVariable id: UUID,
        @RequestParam(required = false, defaultValue = "V7") variant: VpnClientVariant,
    ): String = accounts.renderOvpn(id, variant)

    @GetMapping("/{id}/routeros", produces = ["text/plain"])
    @PreAuthorize("@authz.can('vpn.config.view')")
    @Operation(summary = "Unduh skrip RouterOS akun (berisi kredensial); variant V7 / V6")
    fun routerOs(
        @PathVariable id: UUID,
        @RequestParam(required = false, defaultValue = "V7") variant: VpnClientVariant,
    ): String = accounts.renderRouterOs(id, variant)
}

/**
 * Semua opsional — alur unggulan cukup POST kosong. [label] kosong = nama default, [username]
 * kosong = diturunkan dari label & dijamin unik per hub.
 */
data class GenerateVpnAccountRequest(
    val label: String? = null,
    val deviceType: String? = null,
    val deviceId: UUID? = null,
    val username: String? = null,
) {
    fun toCommand() = GenerateVpnAccountCommand(label, deviceType, deviceId, username)
}
