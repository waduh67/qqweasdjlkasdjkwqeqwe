package com.duluin.ftth.tenancy.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.tenancy.TenantStatus
import java.util.UUID

/**
 * Agregat Tenant — murni domain, menegakkan invariant slug/nama & transisi status.
 * Tidak tahu apa pun soal JPA/HTTP.
 */
class Tenant private constructor(
    val id: UUID,
    slug: String,
    name: String,
    status: TenantStatus,
) {
    var slug: String = slug
        private set

    var name: String = name
        private set

    var status: TenantStatus = status
        private set

    fun rename(newName: String) {
        name = validateName(newName)
    }

    fun suspend() {
        status = TenantStatus.SUSPENDED
    }

    fun activate() {
        status = TenantStatus.ACTIVE
    }

    val isActive: Boolean get() = status == TenantStatus.ACTIVE

    companion object {
        private val SLUG_PATTERN = Regex("^[a-z][a-z0-9-]{1,62}$")

        fun create(slug: String, name: String): Tenant =
            Tenant(UuidV7.generate(), validateSlug(slug), validateName(name), TenantStatus.ACTIVE)

        /** Rekonstruksi dari persistence tanpa re-validasi (data sudah dipercaya). */
        fun rehydrate(id: UUID, slug: String, name: String, status: TenantStatus): Tenant =
            Tenant(id, slug, name, status)

        private fun validateSlug(slug: String): String {
            val normalized = slug.trim().lowercase()
            if (!SLUG_PATTERN.matches(normalized)) {
                throw ValidationException("Slug tenant tidak valid: harus 2-63 karakter, huruf kecil/angka/strip, diawali huruf")
            }
            return normalized
        }

        private fun validateName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.length !in 2..255) throw ValidationException("Nama tenant harus 2-255 karakter")
            return trimmed
        }
    }
}
