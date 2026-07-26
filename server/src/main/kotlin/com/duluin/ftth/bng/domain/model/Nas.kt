package com.duluin.ftth.bng.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/** Vendor BRAS/BNG yang didukung; menentukan adapter yang dipakai agent kelak. */
enum class NasVendor { MIKROTIK, CISCO, JUNIPER, FREERADIUS, OTHER }

/**
 * Satu BRAS/BNG (Network Access Server) milik tenant — router master yang menutup
 * sesi PPPoE dan menjadi klien RADIUS.
 *
 * [coaSecret] adalah rahasia bersama untuk mengirim CoA/Disconnect (Reset Login/isolir);
 * disimpan terenkripsi (batas enkripsi ada di adapter persistence, sama seperti community
 * SNMP OLT). [nasIdentifier] dipakai mencocokkan laporan RADIUS Accounting ke NAS ini.
 * [collectorId] menandai agent on-prem mana yang menjangkau NAS ini.
 *
 * Kredensial kontrol untuk adapter nyata (slice 7d) — dipakai berbeda per vendor:
 * [apiUsername]/[apiSecret]/[apiPort]/[apiUseTls] untuk REST API RouterOS; [apiDatabase]
 * (URL JDBC) + [apiUsername]/[apiSecret] untuk membaca `radacct` FreeRADIUS. Seperti
 * [coaSecret], [apiSecret] plaintext di domain dan hanya terenkripsi di batas persistence.
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
    apiDatabase: String?,
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

    /** User login REST (RouterOS) / basis data (FreeRADIUS). Null = belum diisi. */
    var apiUsername: String? = apiUsername
        private set

    /** Plaintext di domain; terenkripsi di batas persistence (cermin [coaSecret]). */
    var apiSecret: String? = apiSecret
        private set

    /** Port REST RouterOS; null = adapter memakai bawaan (443/80 mengikut [apiUseTls]). */
    var apiPort: Int? = apiPort
        private set

    /** REST RouterOS lewat HTTPS. Diabaikan FreeRADIUS (URL JDBC yang menentukan). */
    var apiUseTls: Boolean = apiUseTls
        private set

    /** URL JDBC basis data RADIUS (FreeRADIUS). Kosong untuk vendor lain. */
    var apiDatabase: String? = apiDatabase
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
        apiDatabase: String?,
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
        this.apiDatabase = validateApiDatabase(apiDatabase)
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
            apiDatabase: String? = null,
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
            apiDatabase = validateApiDatabase(apiDatabase),
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
            apiDatabase: String? = null,
        ): Nas = Nas(
            id, tenantId, name, vendor, address, nasIdentifier, coaSecret, collectorId, enabled,
            apiUsername, apiSecret, apiPort, apiUseTls, apiDatabase,
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

        private fun validateApiDatabase(database: String?): String? {
            val trimmed = database?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > 500) throw ValidationException("URL basis data BRAS maksimal 500 karakter")
            return trimmed
        }
    }
}
