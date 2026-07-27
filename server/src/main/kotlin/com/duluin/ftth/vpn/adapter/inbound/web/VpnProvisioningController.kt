package com.duluin.ftth.vpn.adapter.inbound.web

import com.duluin.ftth.vpn.application.port.inbound.ProvisionVpnNodeUseCase
import com.duluin.ftth.vpn.config.VpnProperties
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * Endpoint provisioning yang dipanggil DARI VPS, diautentikasi token node (bukan bearer JWT) —
 * karena itu ada di allowlist `SecurityConfig`. Tiga peran:
 *  - `GET  install.sh`     → installer satu-perintah (dipipe ke `sudo bash`).
 *  - `POST authenticate`   → `auth-user-pass-verify` OpenVPN; 204 = lolos, 403 = tolak.
 *  - `POST client-connect` → `client-connect` OpenVPN; body = baris `ifconfig-push` IP tetap.
 *
 * Token adalah kredensialnya, jadi jangan pernah membocorkan apakah token valid lewat pesan
 * berbeda: verifikasi kredensial membalas 403 seragam, dan installer 404 bila token tak dikenal.
 */
@RestController
@RequestMapping("/api/vpn/provision")
@Tag(name = "VPN provisioning — dipanggil dari VPS (auth token node)")
class VpnProvisioningController(
    private val provisioning: ProvisionVpnNodeUseCase,
    private val properties: VpnProperties,
) {
    @GetMapping("/install.sh", produces = [MediaType.TEXT_PLAIN_VALUE])
    @Operation(summary = "Installer OpenVPN satu-perintah untuk hub (auth via token node)")
    fun install(@RequestParam token: String): String =
        provisioning.renderInstaller(token, resolveBaseUrl())

    @PostMapping("/authenticate")
    @Operation(summary = "Verifikasi username/password peer (204 lolos, 403 tolak)")
    fun authenticate(
        @RequestParam token: String,
        @RequestParam username: String,
        @RequestParam password: String,
    ): ResponseEntity<Void> =
        if (provisioning.authenticate(token, username, password)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

    @PostMapping("/client-connect", produces = [MediaType.TEXT_PLAIN_VALUE])
    @Operation(summary = "Baris ifconfig-push IP overlay tetap untuk peer (kosong = tolak)")
    fun clientConnect(
        @RequestParam token: String,
        @RequestParam username: String,
    ): ResponseEntity<String> =
        provisioning.clientConnectLine(token, username)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.status(HttpStatus.FORBIDDEN).build()

    /**
     * URL publik yang di-embed ke installer untuk callback: pakai `ftth.vpn.public-base-url`
     * bila diset (sumber kebenaran di prod), jika kosong turunkan dari request unduh installer.
     */
    private fun resolveBaseUrl(): String =
        properties.publicBaseUrl.trimEnd('/').ifBlank {
            ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()
        }
}
