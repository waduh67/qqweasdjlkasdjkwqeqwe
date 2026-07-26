package com.duluin.ftth.bng.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/**
 * Profil layanan (paket) — kecepatan unduh/unggah plus pemetaan ke profil yang
 * dikenal BRAS/RADIUS.
 *
 * Ini objek paket yang bisa DIPAKAI ULANG banyak langganan. Beda dari kolom
 * `packageName`/`bandwidthMbps` di module customer yang berupa teks bebas per
 * langganan: di sinilah atribut jaringan (rate-limit, nama profil RADIUS)
 * menempel, supaya kelak agent bisa menegakkannya ke BRAS. Untuk slice fondasi
 * ini profil hanya "dikelola" — belum dipush ke perangkat mana pun.
 */
class RateProfile private constructor(
    val id: UUID,
    val tenantId: UUID,
    name: String,
    description: String?,
    downMbps: Int,
    upMbps: Int,
    radiusProfileName: String?,
) {
    var name: String = name
        private set

    var description: String? = description
        private set

    var downMbps: Int = downMbps
        private set

    var upMbps: Int = upMbps
        private set

    /** Nama profil yang dikenal BRAS/RADIUS (mis. profil PPP Mikrotik / grup FreeRADIUS). */
    var radiusProfileName: String? = radiusProfileName
        private set

    fun update(name: String, description: String?, downMbps: Int, upMbps: Int, radiusProfileName: String?) {
        this.name = validateName(name)
        this.description = validateDescription(description)
        this.downMbps = validateMbps(downMbps, "Kecepatan unduh")
        this.upMbps = validateMbps(upMbps, "Kecepatan unggah")
        this.radiusProfileName = validateProfileName(radiusProfileName)
    }

    companion object {
        fun create(
            tenantId: UUID,
            name: String,
            description: String?,
            downMbps: Int,
            upMbps: Int,
            radiusProfileName: String?,
        ): RateProfile = RateProfile(
            id = UuidV7.generate(),
            tenantId = tenantId,
            name = validateName(name),
            description = validateDescription(description),
            downMbps = validateMbps(downMbps, "Kecepatan unduh"),
            upMbps = validateMbps(upMbps, "Kecepatan unggah"),
            radiusProfileName = validateProfileName(radiusProfileName),
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            name: String,
            description: String?,
            downMbps: Int,
            upMbps: Int,
            radiusProfileName: String?,
        ): RateProfile = RateProfile(id, tenantId, name, description, downMbps, upMbps, radiusProfileName)

        private fun validateName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.length !in 2..60) throw ValidationException("Nama paket harus 2-60 karakter")
            return trimmed
        }

        private fun validateDescription(description: String?): String? {
            val trimmed = description?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > 200) throw ValidationException("Keterangan paket maksimal 200 karakter")
            return trimmed
        }

        private fun validateMbps(mbps: Int, label: String): Int {
            if (mbps !in 1..100_000) throw ValidationException("$label harus 1-100000 Mbps")
            return mbps
        }

        private fun validateProfileName(name: String?): String? {
            val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > 100) throw ValidationException("Nama profil RADIUS maksimal 100 karakter")
            return trimmed
        }
    }
}
