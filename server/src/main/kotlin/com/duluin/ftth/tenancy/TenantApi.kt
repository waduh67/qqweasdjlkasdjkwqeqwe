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

    /**
     * Id seluruh tenant aktif. Dipakai pekerjaan terjadwal lintas-tenant (mis.
     * sinkronisasi CPE dari ACS) yang berjalan di luar konteks request dan perlu
     * memasang tenant satu per satu lewat [com.duluin.ftth.common.tenant.TenantContext.runAs].
     */
    fun findActiveTenantIds(): List<UUID>

    /** Buat tenant bila slug belum ada; idempotent. Mengembalikan tenant (baru atau lama). */
    fun ensureTenant(slug: String, name: String): TenantRef

    /**
     * Suspend tenant (mis. saat langganan SaaS lewat masa tenggang di `platformbilling`).
     * Di-expose lewat [TenantApi] — bukan lewat use case web internal — agar module lain
     * bisa memicu suspend tanpa menembus batas enkapsulasi tenancy.
     */
    fun suspend(id: UUID): TenantRef

    /** Aktifkan kembali tenant yang tersuspend (mis. saat tunggakan langganan lunas). */
    fun activate(id: UUID): TenantRef
}
