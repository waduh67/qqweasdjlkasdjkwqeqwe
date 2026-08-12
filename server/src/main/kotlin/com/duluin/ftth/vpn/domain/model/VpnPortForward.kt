package com.duluin.ftth.vpn.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/**
 * Transport yang di-DNAT hub. Nyaris semua layanan manajemen berjalan di atas TCP (Winbox,
 * API, SSH, WebFig, Telnet); [UDP] disediakan untuk yang tidak — terutama SNMP 161 bila
 * operator mengarahkan NMS luar ke perangkat lewat hub.
 */
enum class VpnForwardProtocol { TCP, UDP }

/**
 * Satu pemetaan port pada hub: `IP_HUB:publicPort` → `overlayIp:devicePort`.
 *
 * Dulu pemetaan ini dipaku ke Winbox 8291 di dalam installer. Di lapangan itu patah dua kali:
 * (1) banyak ISP memindah port Winbox demi keamanan — begitu dipindah, perangkatnya jadi tak
 * terjangkau padahal tunnelnya sehat; (2) satu perangkat lazim perlu dijangkau lewat lebih dari
 * satu layanan (Winbox untuk teknisi, API untuk otomasi pihak ketiga, SSH untuk yang biasa CLI).
 * Karena itu penerusan port kini data milik akun, bukan konstanta program.
 *
 * [publicPort] dialokasikan sistem dan TAK PERNAH berubah: begitu diberikan, operator menyimpannya
 * di bookmark Winbox/daftar perangkatnya. Yang boleh diubah hanya sasarannya ([devicePort],
 * [protocol]) dan namanya ([label]) — persis yang berubah ketika port di perangkat dipindah.
 */
class VpnPortForward private constructor(
    val id: UUID,
    val publicPort: Int,
    label: String,
    devicePort: Int,
    protocol: VpnForwardProtocol,
) {
    /** Nama layanan untuk manusia (mis. "Winbox", "SSH") — hiasan, tak dipakai iptables. */
    var label: String = label
        private set

    /** Port layanan DI PERANGKAT yang dituju. Inilah yang ikut berubah saat port di router dipindah. */
    var devicePort: Int = devicePort
        private set

    var protocol: VpnForwardProtocol = protocol
        private set

    /** Arahkan ulang ke layanan/port lain pada perangkat yang sama; [publicPort] sengaja tetap. */
    fun retarget(label: String?, devicePort: Int, protocol: VpnForwardProtocol) {
        this.devicePort = validatePort(devicePort, "Port layanan perangkat")
        this.label = resolveLabel(label, devicePort)
        this.protocol = protocol
    }

    companion object {
        /** Port Winbox bawaan Mikrotik — tebakan pertama yang benar untuk hampir semua perangkat. */
        const val WINBOX_PORT = 8291

        /** Batas penerusan per akun: menahan satu akun menghabiskan rentang port publik hub. */
        const val MAX_PER_PEER = 10

        private const val MAX_LABEL = 40

        /**
         * Nama layanan yang sudah umum dikenal, dipakai saat operator tak menamai sendiri. Isinya
         * port BAWAAN tiap layanan; perangkat yang portnya dipindah tetap memakai nama ini karena
         * operator memilih presetnya, bukan karena angkanya cocok.
         */
        private val WELL_KNOWN = mapOf(
            WINBOX_PORT to "Winbox",
            8728 to "API",
            8729 to "API-SSL",
            22 to "SSH",
            23 to "Telnet",
            80 to "WebFig",
            443 to "WebFig HTTPS",
            161 to "SNMP",
            2000 to "Bandwidth test",
        )

        fun create(
            publicPort: Int,
            label: String?,
            devicePort: Int,
            protocol: VpnForwardProtocol,
        ): VpnPortForward = VpnPortForward(
            id = UuidV7.generate(),
            publicPort = validatePort(publicPort, "Port publik hub"),
            label = resolveLabel(label, devicePort),
            devicePort = validatePort(devicePort, "Port layanan perangkat"),
            protocol = protocol,
        )

        /** Penerusan bawaan saat akun lahir: Winbox, karena itu yang dipakai 9 dari 10 teknisi. */
        fun winbox(publicPort: Int): VpnPortForward =
            create(publicPort, null, WINBOX_PORT, VpnForwardProtocol.TCP)

        fun rehydrate(
            id: UUID,
            publicPort: Int,
            label: String,
            devicePort: Int,
            protocol: VpnForwardProtocol,
        ): VpnPortForward = VpnPortForward(id, publicPort, label, devicePort, protocol)

        /** Nama yang enak dibaca bila operator membiarkannya kosong. */
        fun suggestLabel(devicePort: Int): String = WELL_KNOWN[devicePort] ?: "Port $devicePort"

        private fun resolveLabel(label: String?, devicePort: Int): String {
            val trimmed = label?.trim()?.takeIf { it.isNotEmpty() } ?: suggestLabel(devicePort)
            if (trimmed.length > MAX_LABEL) throw ValidationException("Nama penerusan maksimal $MAX_LABEL karakter")
            return trimmed
        }

        private fun validatePort(port: Int, what: String): Int {
            if (port !in 1..65535) throw ValidationException("$what harus 1-65535")
            return port
        }
    }
}
