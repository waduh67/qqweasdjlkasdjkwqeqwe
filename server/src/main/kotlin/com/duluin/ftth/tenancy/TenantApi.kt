package com.duluin.ftth.tenancy

import java.util.UUID

/**
 * Kontrak publik module tenancy untuk module lain (mis. iam saat onboarding &
 * saat login me-resolve slug → tenant).
 */
interface TenantApi {

    fun findById(id: UUID): TenantRef?

    fun findBySlug(slug: String): TenantRef?

    /** Sama seperti [findById] tetapi melempar NotFound bila tidak ada. */
    fun requireById(id: UUID): TenantRef

    /** Id tenant "platform" — rumah para platform admin. */
    fun platformTenantId(): UUID

    /** Buat tenant bila slug belum ada; idempotent. Mengembalikan tenant (baru atau lama). */
    fun ensureTenant(slug: String, name: String): TenantRef
}
