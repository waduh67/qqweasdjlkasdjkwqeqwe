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
 * [coaSecret] adalah rahasia bersama untuk mengirim CoA/Disconnect (fitur "Reset
 * Login"/isolir di slice berikutnya); disimpan terenkripsi (batas enkripsi ada di
 * adapter persistence, sama seperti community SNMP OLT). [nasIdentifier] dipakai
 * mencocokkan laporan RADIUS Accounting ke NAS ini. [collectorId] menandai agent
 * on-prem mana yang menjangkau NAS ini — belum dipakai di slice fondasi.
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

    @Suppress("LongParameterList")
    fun update(
        name: String,
        vendor: NasVendor,
        address: String?,
        nasIdentifier: String?,
        coaSecret: String?,
        collectorId: UUID?,
        enabled: Boolean,
    ) {
        this.name = validateName(name)
        this.vendor = vendor
        this.address = validateAddress(address)
        this.nasIdentifier = validateIdentifier(nasIdentifier)
        // Null berarti "biarkan apa adanya" agar rahasia tak terhapus tanpa sengaja
        // saat operator menyunting field lain; string kosong pun diperlakukan sama.
        coaSecret?.trim()?.takeIf { it.isNotEmpty() }?.let { this.coaSecret = validateSecret(it) }
        this.collectorId = collectorId
        this.enabled = enabled
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
        ): Nas = Nas(
            id = UuidV7.generate(),
            tenantId = tenantId,
            name = validateName(name),
            vendor = vendor,
            address = validateAddress(address),
            nasIdentifier = validateIdentifier(nasIdentifier),
            coaSecret = coaSecret?.trim()?.takeIf { it.isNotEmpty() }?.let { validateSecret(it) },
            collectorId = collectorId,
            enabled = true,
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
        ): Nas = Nas(id, tenantId, name, vendor, address, nasIdentifier, coaSecret, collectorId, enabled)

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

        private fun validateSecret(secret: String): String {
            if (secret.length > 255) throw ValidationException("Secret CoA maksimal 255 karakter")
            return secret
        }
    }
}
