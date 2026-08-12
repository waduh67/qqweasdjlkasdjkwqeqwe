package com.duluin.ftth.vpn.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
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
 * dialokasikan dari subnet server. [forwards] adalah daftar port hub yang di-DNAT ke layanan
 * perangkat → operator meremote lewat `IP_HUB:publicPort` tanpa ikut men-dial tunnel.
 * [password] adalah rahasia: plaintext di domain, terenkripsi di batas persistence.
 * [deviceType]/[deviceId] hanya label bebas atas perangkat yang dijangkau (TANPA FK, boleh
 * null). Liveness ([online] + [lastHandshakeAt]) dilaporkan hub lewat callback OpenVPN
 * connect/disconnect — bukan status administratif, melainkan cerminan koneksi nyata.
 */
class VpnPeer private constructor(
    val id: UUID,
    val tenantId: UUID,
    val serverId: UUID,
    val name: String,
    val username: String,
    val overlayIp: String,
    forwards: List<VpnPortForward>,
    status: VpnPeerStatus,
    val deviceType: String?,
    val deviceId: UUID?,
    lastHandshakeAt: Instant?,
    online: Boolean,
    password: String,
) {
    private val mutableForwards: MutableList<VpnPortForward> =
        forwards.sortedBy { it.publicPort }.toMutableList()

    /** Penerusan port akun ini, urut port publik. Kosong = perangkat sengaja tak diekspos. */
    val forwards: List<VpnPortForward> get() = mutableForwards.toList()

    /**
     * Port publik utama (yang terendah — biasanya Winbox, karena ia dialokasikan lebih dulu).
     * Null bila semua penerusan dihapus: akunnya tetap sah, perangkatnya hanya tak punya pintu
     * dari internet dan cuma terjangkau dari dalam tunnel.
     */
    val remotePort: Int? get() = mutableForwards.firstOrNull()?.publicPort

    var status: VpnPeerStatus = status
        private set

    /** Waktu peer terakhir dilaporkan hub terhubung; null bila hub belum pernah melaporkannya. */
    var lastHandshakeAt: Instant? = lastHandshakeAt
        private set

    /** True selagi hub melaporkan peer terhubung; jadi false saat hub melaporkan putus. */
    var online: Boolean = online
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

    /** Hub melapor peer BARU TERHUBUNG (callback `client-connect` OpenVPN): tandai online + catat waktu. */
    fun markConnected(at: Instant) {
        lastHandshakeAt = at
        online = true
    }

    /** Hub melapor peer PUTUS (callback `client-disconnect`): offline; [lastHandshakeAt] tetap sebagai jejak. */
    fun markDisconnected() {
        online = false
    }

    /**
     * Tambah penerusan ke satu layanan lagi di perangkat yang sama. [publicPort] sudah
     * dialokasikan pemanggil dari rentang hub (unik lintas-tenant), jadi di sini cukup dijaga
     * agar tak dobel di dalam akun ini.
     */
    fun addForward(
        publicPort: Int,
        label: String?,
        devicePort: Int,
        protocol: VpnForwardProtocol,
    ): VpnPortForward {
        if (mutableForwards.size >= VpnPortForward.MAX_PER_PEER) {
            throw ValidationException("Satu akun VPN maksimal ${VpnPortForward.MAX_PER_PEER} penerusan port")
        }
        if (mutableForwards.any { it.publicPort == publicPort }) {
            throw ConflictException("Port publik $publicPort sudah dipakai akun ini")
        }
        val forward = VpnPortForward.create(publicPort, label, devicePort, protocol)
        mutableForwards += forward
        mutableForwards.sortBy { it.publicPort }
        return forward
    }

    /** Arahkan ulang satu penerusan (mis. port Winbox perangkat dipindah 8291 → 9291). */
    fun retargetForward(forwardId: UUID, label: String?, devicePort: Int, protocol: VpnForwardProtocol) {
        forward(forwardId).retarget(label, devicePort, protocol)
    }

    /** Cabut penerusan: portnya kembali ke kolam hub dan perangkat kehilangan pintu itu. */
    fun removeForward(forwardId: UUID) {
        mutableForwards.remove(forward(forwardId))
    }

    private fun forward(forwardId: UUID): VpnPortForward =
        mutableForwards.firstOrNull { it.id == forwardId }
            ?: throw NotFoundException("Penerusan port $forwardId tidak ditemukan pada akun ini")

    companion object {
        @Suppress("LongParameterList")
        fun create(
            tenantId: UUID,
            serverId: UUID,
            name: String,
            username: String,
            overlayIp: String,
            remotePort: Int,
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
            // Akun baru lahir dengan satu pintu: Winbox. Operator boleh menambah/mengubahnya.
            forwards = listOf(VpnPortForward.winbox(remotePort)),
            status = VpnPeerStatus.ENABLED,
            deviceType = deviceType?.trim()?.takeIf { it.isNotEmpty() },
            deviceId = deviceId,
            lastHandshakeAt = null,
            online = false,
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
            forwards: List<VpnPortForward>,
            status: VpnPeerStatus,
            deviceType: String?,
            deviceId: UUID?,
            lastHandshakeAt: Instant?,
            online: Boolean,
            password: String,
        ): VpnPeer = VpnPeer(
            id, tenantId, serverId, name, username, overlayIp, forwards, status,
            deviceType, deviceId, lastHandshakeAt, online, password,
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
