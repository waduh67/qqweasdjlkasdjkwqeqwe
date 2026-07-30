package com.duluin.ftth.bng.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/** Vendor BRAS/BNG yang didukung; menentukan adapter yang dipakai agent kelak. */
enum class NasVendor { MIKROTIK, CISCO, JUNIPER, OTHER }

/**
 * Bagaimana server pusat menjangkau NAS ini untuk kontrol sesi (DAE CoA/Disconnect).
 * Auth & accounting TAK pernah butuh ini — Mikrotik menembak keluar ke server RADIUS.
 * Hanya jalur-turun (memutus/menurunkan sesi hidup) yang perlu tahu rute balik:
 *  - [DIRECT]    NAS ber-IP-publik: server tembak `:3799` langsung.
 *  - [VPN]       NAS di-NAT tapi ikut overlay VPN: server tembak lewat IP overlay (S2c).
 *  - [COLLECTOR] NAS di-NAT tanpa VPN: titipkan ke agent on-prem yang sekamar dengan NAS.
 *  - [NONE]      tak terjangkau apa pun: degradasi anggun — perubahan berlaku saat login ulang.
 */
enum class NasReachability { DIRECT, VPN, COLLECTOR, NONE }

/**
 * Satu BRAS/BNG (Network Access Server) milik tenant — router master yang menutup
 * sesi PPPoE dan menjadi klien RADIUS.
 *
 * [coaSecret] adalah rahasia bersama untuk mengirim CoA/Disconnect (Reset Login/isolir);
 * disimpan terenkripsi (batas enkripsi ada di adapter persistence, sama seperti community
 * SNMP OLT). [nasIdentifier] dipakai mencocokkan laporan RADIUS Accounting ke NAS ini.
 * [collectorId] menandai agent on-prem mana yang menjangkau NAS ini.
 *
 * Kredensial kontrol untuk adapter nyata — [apiUsername]/[apiSecret]/[apiPort]/[apiUseTls]
 * dipakai REST API RouterOS (vendor MIKROTIK). Seperti [coaSecret], [apiSecret] plaintext
 * di domain dan hanya terenkripsi di batas persistence.
 */
class Nas private constructor(
    val id: UUID,
    val tenantId: UUID,
    name: String,
    vendor: NasVendor,
    address: String?,
    nasIdentifier: String?,
    coaSecret: String?,
    collectorId: UUID?,
    enabled: Boolean,
    apiUsername: String?,
    apiSecret: String?,
    apiPort: Int?,
    apiUseTls: Boolean,
    reachability: NasReachability,
) {
    var name: String = name
        private set

    var vendor: NasVendor = vendor
        private set

    /** Alamat manajemen BRAS (host/IP) — sasaran CoA dan tujuan agent. */
    var address: String? = address
        private set

    var nasIdentifier: String? = nasIdentifier
        private set

    /** Plaintext di domain; adapter persistence yang mengenkripsi ke DB. Null = belum diisi. */
    var coaSecret: String? = coaSecret
        private set

    var collectorId: UUID? = collectorId
        private set

    var enabled: Boolean = enabled
        private set

    /** User login REST RouterOS. Null = belum diisi. */
    var apiUsername: String? = apiUsername
        private set

    /** Plaintext di domain; terenkripsi di batas persistence (cermin [coaSecret]). */
    var apiSecret: String? = apiSecret
        private set

    /** Port REST RouterOS; null = adapter memakai bawaan (443/80 mengikut [apiUseTls]). */
    var apiPort: Int? = apiPort
        private set

    /** REST RouterOS lewat HTTPS. */
    var apiUseTls: Boolean = apiUseTls
        private set

    /**
     * Rute kontrol sesi (DAE) ke NAS ini. Ditetapkan saat pembuatan dan dijaga lintas
     * [update] (bukan field form biasa; S3 yang menyambungkannya ke form self-service),
     * agar sunting field lain tak diam-diam mereset rute jadi COLLECTOR.
     */
    var reachability: NasReachability = reachability
        private set

    @Suppress("LongParameterList")
    fun update(
        name: String,
        vendor: NasVendor,
        address: String?,
        nasIdentifier: String?,
        coaSecret: String?,
        collectorId: UUID?,
        enabled: Boolean,
        apiUsername: String?,
        apiSecret: String?,
        apiPort: Int?,
        apiUseTls: Boolean,
    ) {
        this.name = validateName(name)
        this.vendor = vendor
        this.address = validateAddress(address)
        this.nasIdentifier = validateIdentifier(nasIdentifier)
        // Null berarti "biarkan apa adanya" agar rahasia tak terhapus tanpa sengaja
        // saat operator menyunting field lain; string kosong pun diperlakukan sama.
        coaSecret?.trim()?.takeIf { it.isNotEmpty() }?.let { this.coaSecret = validateSecret(it, "Secret CoA") }
        this.collectorId = collectorId
        this.enabled = enabled
        this.apiUsername = validateApiUsername(apiUsername)
        // Sama seperti coaSecret: null/kosong = biarkan apa adanya, tak menimpa yang ada.
        apiSecret?.trim()?.takeIf { it.isNotEmpty() }?.let { this.apiSecret = validateSecret(it, "Password kontrol BRAS") }
        this.apiPort = validateApiPort(apiPort)
        this.apiUseTls = apiUseTls
    }

    companion object {
        @Suppress("LongParameterList")
        fun create(
            tenantId: UUID,
            name: String,
            vendor: NasVendor,
            address: String?,
            nasIdentifier: String?,
            coaSecret: String?,
            collectorId: UUID?,
            apiUsername: String? = null,
            apiSecret: String? = null,
            apiPort: Int? = null,
            apiUseTls: Boolean = true,
            reachability: NasReachability = NasReachability.COLLECTOR,
        ): Nas = Nas(
            id = UuidV7.generate(),
            tenantId = tenantId,
            name = validateName(name),
            vendor = vendor,
            address = validateAddress(address),
            nasIdentifier = validateIdentifier(nasIdentifier),
            coaSecret = coaSecret?.trim()?.takeIf { it.isNotEmpty() }?.let { validateSecret(it, "Secret CoA") },
            collectorId = collectorId,
            enabled = true,
            apiUsername = validateApiUsername(apiUsername),
            apiSecret = apiSecret?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { validateSecret(it, "Password kontrol BRAS") },
            apiPort = validateApiPort(apiPort),
            apiUseTls = apiUseTls,
            reachability = reachability,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            name: String,
            vendor: NasVendor,
            address: String?,
            nasIdentifier: String?,
            coaSecret: String?,
            collectorId: UUID?,
            enabled: Boolean,
            apiUsername: String? = null,
            apiSecret: String? = null,
            apiPort: Int? = null,
            apiUseTls: Boolean = true,
            reachability: NasReachability = NasReachability.COLLECTOR,
        ): Nas = Nas(
            id, tenantId, name, vendor, address, nasIdentifier, coaSecret, collectorId, enabled,
            apiUsername, apiSecret, apiPort, apiUseTls, reachability,
        )

        private fun validateName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.length !in 2..80) throw ValidationException("Nama BRAS harus 2-80 karakter")
            return trimmed
        }

        private fun validateAddress(address: String?): String? {
            val trimmed = address?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > 255) throw ValidationException("Alamat BRAS maksimal 255 karakter")
            return trimmed
        }

        private fun validateIdentifier(identifier: String?): String? {
            val trimmed = identifier?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > 128) throw ValidationException("NAS-Identifier maksimal 128 karakter")
            return trimmed
        }

        private fun validateSecret(secret: String, label: String): String {
            if (secret.length > 255) throw ValidationException("$label maksimal 255 karakter")
            return secret
        }

        private fun validateApiUsername(username: String?): String? {
            val trimmed = username?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > 128) throw ValidationException("User kontrol BRAS maksimal 128 karakter")
            return trimmed
        }

        private fun validateApiPort(port: Int?): Int? {
            if (port != null && port !in 1..65_535) throw ValidationException("Port kontrol BRAS harus 1-65535")
            return port
        }
    }
}
