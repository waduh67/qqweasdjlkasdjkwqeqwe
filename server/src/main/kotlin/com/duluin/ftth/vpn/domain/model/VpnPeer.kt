package com.duluin.ftth.vpn.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant
import java.util.UUID

/** Status peer: ENABLED boleh terhubung, DISABLED diblokir. */
enum class VpnPeerStatus { ENABLED, DISABLED }

/**
 * Satu perangkat terkelola yang men-dial hub OpenVPN dan menempati satu IP overlay tetap.
 *
 * [serverId] menaut ke [VpnServer] (referensi intra-module, FK diperbolehkan). [username]
 * unik per server (dipakai sebagai identitas login OpenVPN dan common-name). [overlayIp]
 * dialokasikan dari subnet server. [password] adalah rahasia: plaintext di domain,
 * terenkripsi di batas persistence. [deviceType]/[deviceId] hanya label bebas atas
 * perangkat yang dijangkau (TANPA FK, boleh null). [lastHandshakeAt] dicadangkan untuk
 * liveness kelak — selalu null untuk saat ini.
 */
class VpnPeer private constructor(
    val id: UUID,
    val tenantId: UUID,
    val serverId: UUID,
    val name: String,
    val username: String,
    val overlayIp: String,
    status: VpnPeerStatus,
    val deviceType: String?,
    val deviceId: UUID?,
    val lastHandshakeAt: Instant?,
    password: String,
) {
    var status: VpnPeerStatus = status
        private set

    /** Plaintext di domain; adapter persistence yang mengenkripsi ke DB. */
    var password: String = password
        private set

    fun enable() {
        status = VpnPeerStatus.ENABLED
    }

    fun disable() {
        status = VpnPeerStatus.DISABLED
    }

    fun rotatePassword(newPassword: String) {
        this.password = validatePassword(newPassword)
    }

    companion object {
        @Suppress("LongParameterList")
        fun create(
            tenantId: UUID,
            serverId: UUID,
            name: String,
            username: String,
            overlayIp: String,
            password: String,
            deviceType: String?,
            deviceId: UUID?,
        ): VpnPeer = VpnPeer(
            id = UuidV7.generate(),
            tenantId = tenantId,
            serverId = serverId,
            name = validateName(name),
            username = validateUsername(username),
            overlayIp = validateOverlayIp(overlayIp),
            status = VpnPeerStatus.ENABLED,
            deviceType = deviceType?.trim()?.takeIf { it.isNotEmpty() },
            deviceId = deviceId,
            lastHandshakeAt = null,
            password = validatePassword(password),
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            serverId: UUID,
            name: String,
            username: String,
            overlayIp: String,
            status: VpnPeerStatus,
            deviceType: String?,
            deviceId: UUID?,
            lastHandshakeAt: Instant?,
            password: String,
        ): VpnPeer = VpnPeer(
            id, tenantId, serverId, name, username, overlayIp, status,
            deviceType, deviceId, lastHandshakeAt, password,
        )

        private val USERNAME_PATTERN = Regex("^[a-zA-Z0-9._-]+$")

        private fun validateName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isBlank()) throw ValidationException("Nama peer VPN wajib diisi")
            if (trimmed.length > 100) throw ValidationException("Nama peer VPN maksimal 100 karakter")
            return trimmed
        }

        private fun validateUsername(username: String): String {
            val trimmed = username.trim()
            if (trimmed.isBlank()) throw ValidationException("Username peer VPN wajib diisi")
            if (!USERNAME_PATTERN.matches(trimmed)) {
                throw ValidationException("Username peer VPN hanya boleh huruf, angka, titik, minus, dan garis bawah")
            }
            if (trimmed.length > 64) throw ValidationException("Username peer VPN maksimal 64 karakter")
            return trimmed
        }

        private fun validateOverlayIp(overlayIp: String): String {
            val trimmed = overlayIp.trim()
            if (trimmed.isBlank()) throw ValidationException("IP overlay peer VPN wajib diisi")
            return trimmed
        }

        private fun validatePassword(password: String): String {
            if (password.isBlank()) throw ValidationException("Password peer VPN wajib diisi")
            return password
        }
    }
}
