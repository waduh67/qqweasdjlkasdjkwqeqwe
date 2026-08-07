package com.duluin.ftth.iam

import java.util.UUID

/**
 * Kontrak publik module iam untuk module lain (workorder saat menugaskan dan
 * menampilkan nama teknisi).
 *
 * Sengaja tidak mengekspos agregat `User`: pemanggil hanya perlu identitas ringkas
 * seorang pengguna, bukan role/scope/hash-nya. Id pengguna disimpan module lain
 * sebagai `uuid` polos tanpa FK — kontrak inilah yang meresolusi id menjadi nama.
 */
interface IamApi {

    /** Identitas ringkas seorang pengguna, atau `null` bila tak ada di tenant aktif. */
    fun findUser(id: UUID): UserRef?

    /** Resolusi sekumpulan id pengguna sekaligus; id yang tak ditemukan diabaikan. */
    fun usersByIds(ids: Set<UUID>): List<UserRef>

    /**
     * Email kontak penagihan sebuah tenant (login admin onboarding pertama), atau `null` bila
     * tenant tak punya user. Aman dipanggil dari konteks platform tanpa tenant aktif (mis.
     * scheduler langganan) — resolusi lewat indeks login non-RLS, bukan agregat user ter-scope.
     */
    fun primaryEmailForTenant(tenantId: UUID): String?
}

/** Pandangan ringkas seorang pengguna untuk konsumen lintas-module. */
data class UserRef(
    val id: UUID,
    val name: String,
    val email: String,
    val active: Boolean,
)
