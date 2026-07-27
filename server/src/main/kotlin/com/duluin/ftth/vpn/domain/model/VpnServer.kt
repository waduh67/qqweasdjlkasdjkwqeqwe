package com.duluin.ftth.vpn.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/** Protokol transport OpenVPN yang dipakai hub. */
enum class VpnProtocol { UDP, TCP }

/** Status hub VPN: ACTIVE menerima dial-in peer, DISABLED tidak. */
enum class VpnServerStatus { ACTIVE, DISABLED }

/**
 * Hub OpenVPN yang dijalankan PLATFORM (operator SaaS) di VPS ber-IP publik — bukan milik
 * tenant. Beberapa hub bisa berdampingan; tenant tinggal men-generate akun dan sistem
 * menautkannya ke salah satu hub yang tersedia. Perangkat tenant men-dial ke sini dan
 * memperoleh IP overlay tetap yang bisa di-Winbox/SSH.
 *
 * [caCertPem] (PEM sertifikat CA, wajib untuk membangun config klien) dan [tlsAuthKey]
 * (opsional ta.key) adalah rahasia: plaintext di domain, terenkripsi di batas persistence
 * (cermin secret CoA/SNMP di module lain). [tunnelCidr] menentukan blok overlay tempat
 * peer dialokasikan; alamat network+1 dicadangkan untuk hub sendiri. TANPA tenantId: hub
 * adalah infrastruktur platform, dikelola hanya di dashboard admin platform.
 */
class VpnServer private constructor(
    val id: UUID,
    name: String,
    host: String,
    port: Int,
    protocol: VpnProtocol,
    tunnelCidr: String,
    status: VpnServerStatus,
    caCertPem: String?,
    tlsAuthKey: String?,
    caKeyPem: String?,
    serverCertPem: String?,
    serverKeyPem: String?,
) {
    var name: String = name
        private set

    /** FQDN/IP publik yang di-dial klien. */
    var host: String = host
        private set

    var port: Int = port
        private set

    var protocol: VpnProtocol = protocol
        private set

    /** Blok overlay tunnel (CIDR IPv4), mis. `10.8.0.0/24`. */
    var tunnelCidr: String = tunnelCidr
        private set

    var status: VpnServerStatus = status
        private set

    /** Plaintext di domain; adapter persistence yang mengenkripsi ke DB. Null = belum diisi. */
    var caCertPem: String? = caCertPem
        private set

    /** Kunci ta.key opsional (tls-auth); plaintext di domain, terenkripsi di batas persistence. */
    var tlsAuthKey: String? = tlsAuthKey
        private set

    /** Kunci privat CA (menandatangani sertifikat server) — RAHASIA, terenkripsi di persistence. */
    var caKeyPem: String? = caKeyPem
        private set

    /** Sertifikat server (publik, EKU serverAuth); disimpan apa adanya. */
    var serverCertPem: String? = serverCertPem
        private set

    /** Kunci privat server — RAHASIA, terenkripsi di persistence. */
    var serverKeyPem: String? = serverKeyPem
        private set

    /** True bila CA + sertifikat server lengkap → siap membuat installer & config klien. */
    val pkiReady: Boolean
        get() = caCertPem != null && caKeyPem != null && serverCertPem != null && serverKeyPem != null

    fun enable() {
        status = VpnServerStatus.ACTIVE
    }

    fun disable() {
        status = VpnServerStatus.DISABLED
    }

    fun rename(name: String) {
        this.name = validateName(name)
    }

    /** Ubah titik dial (host/port/protokol) tanpa menyentuh subnet atau kredensial. */
    fun updateEndpoint(host: String, port: Int, protocol: VpnProtocol) {
        this.host = validateHost(host)
        this.port = validatePort(port)
        this.protocol = protocol
    }

    /** Set/hapus kredensial CA & tls-auth; kosong/blank diperlakukan sebagai null (belum diisi). */
    fun setCredentials(caCertPem: String?, tlsAuthKey: String?) {
        this.caCertPem = caCertPem?.trim()?.takeIf { it.isNotEmpty() }
        this.tlsAuthKey = tlsAuthKey?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Pasang materi PKI yang diterbitkan aplikasi (CA menandatangani sertifikat server).
     * Dipanggil saat hub dibuat sehingga operator tak perlu menjalankan easy-rsa sendiri.
     */
    fun attachPki(caCertPem: String, caKeyPem: String, serverCertPem: String, serverKeyPem: String) {
        this.caCertPem = caCertPem.trim()
        this.caKeyPem = caKeyPem.trim()
        this.serverCertPem = serverCertPem.trim()
        this.serverKeyPem = serverKeyPem.trim()
    }

    companion object {
        fun create(
            name: String,
            host: String,
            port: Int,
            protocol: VpnProtocol,
            tunnelCidr: String,
        ): VpnServer = VpnServer(
            id = UuidV7.generate(),
            name = validateName(name),
            host = validateHost(host),
            port = validatePort(port),
            protocol = protocol,
            tunnelCidr = validateCidr(tunnelCidr),
            status = VpnServerStatus.ACTIVE,
            caCertPem = null,
            tlsAuthKey = null,
            caKeyPem = null,
            serverCertPem = null,
            serverKeyPem = null,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            name: String,
            host: String,
            port: Int,
            protocol: VpnProtocol,
            tunnelCidr: String,
            status: VpnServerStatus,
            caCertPem: String?,
            tlsAuthKey: String?,
            caKeyPem: String?,
            serverCertPem: String?,
            serverKeyPem: String?,
        ): VpnServer = VpnServer(
            id, name, host, port, protocol, tunnelCidr, status,
            caCertPem, tlsAuthKey, caKeyPem, serverCertPem, serverKeyPem,
        )

        private fun validateName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isBlank()) throw ValidationException("Nama server VPN wajib diisi")
            if (trimmed.length > 100) throw ValidationException("Nama server VPN maksimal 100 karakter")
            return trimmed
        }

        private fun validateHost(host: String): String {
            val trimmed = host.trim()
            if (trimmed.isBlank()) throw ValidationException("Host server VPN wajib diisi")
            if (trimmed.length > 255) throw ValidationException("Host server VPN maksimal 255 karakter")
            return trimmed
        }

        private fun validatePort(port: Int): Int {
            if (port !in 1..65_535) throw ValidationException("Port server VPN harus 1-65535")
            return port
        }

        private fun validateCidr(cidr: String): String {
            // Validasi lewat VO; melempar ValidationException bila bukan CIDR IPv4 8..30.
            TunnelSubnet.parse(cidr)
            return cidr.trim()
        }
    }
}
