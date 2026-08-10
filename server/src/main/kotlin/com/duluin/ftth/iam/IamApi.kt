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

    /**
     * Resolusi sekumpulan id area menjadi namanya; id yang tak ditemukan diabaikan.
     *
     * Area hidup di module iam karena ia dimensi SCOPE RBAC, tapi module lain menyimpannya
     * sebagai `uuid` polos (pelanggan, work order). Kontrak inilah yang menerjemahkan id itu jadi
     * label yang bisa dibaca manusia — mis. laporan pendapatan per wilayah.
     */
    fun areasByIds(ids: Set<UUID>): List<AreaRef>
}

/** Pandangan ringkas seorang pengguna untuk konsumen lintas-module. */
data class UserRef(
    val id: UUID,
    val name: String,
    val email: String,
    val active: Boolean,
)

/** Pandangan ringkas sebuah area/wilayah operasional untuk konsumen lintas-module. */
data class AreaRef(
    val id: UUID,
    val code: String,
    val name: String,
)
