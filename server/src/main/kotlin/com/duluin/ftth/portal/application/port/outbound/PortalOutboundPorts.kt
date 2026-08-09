package com.duluin.ftth.portal.application.port.outbound

import com.duluin.ftth.portal.domain.model.PortalCredential
import com.duluin.ftth.portal.domain.model.PortalIdentityKind
import com.duluin.ftth.portal.domain.model.PortalPasswordReset
import com.duluin.ftth.portal.domain.model.PortalRefreshToken
import java.time.Instant
import java.util.UUID

/**
 * Port persistence kredensial portal. Tenant-aware (@TenantId + RLS) → pencarian
 * ter-scope tenant aktif otomatis; login yang sama di tenant berbeda tak saling tabrak.
 */
interface PortalCredentialRepository {

    fun save(credential: PortalCredential): PortalCredential

    fun findByLogin(login: String): PortalCredential?

    fun findByCustomerId(customerId: UUID): PortalCredential?
}

/**
 * Port persistence refresh-token portal. SENGAJA bukan tenant-aware: lookup by hash
 * terjadi sebelum tenant context terbentuk (saat refresh/logout).
 */
interface PortalRefreshTokenRepository {

    fun save(token: PortalRefreshToken): PortalRefreshToken

    fun findByTokenHash(tokenHash: String): PortalRefreshToken?

    fun revokeAllForCustomer(customerId: UUID)
}

/** Port penerbit access-token portal (JWT HS256, secret terpisah dari operator). */
interface PortalAccessTokenIssuer {

    fun issue(customerId: UUID, tenantId: UUID, login: String, name: String): PortalIssuedToken
}

data class PortalIssuedToken(
    val value: String,
    val expiresAt: Instant,
)

/** Port hashing password portal — implementasi (BCrypt bersama) berada di adapter. */
interface PortalPasswordHasher {

    fun hash(rawPassword: String): String

    fun matches(rawPassword: String, passwordHash: String): Boolean
}

/**
 * Indeks identitas → (tenant, pelanggan) yang membuat pelanggan bisa masuk TANPA menyebut
 * ISP-nya. SENGAJA bukan tenant-aware, sama alasannya dengan [PortalRefreshTokenRepository]:
 * pencarian terjadi ketika tenant justru BELUM diketahui — itulah yang sedang dicari.
 *
 * Isinya hanya penunjuk (tak ada password/hash), jadi membacanya lintas-tenant tak
 * membocorkan apa pun yang bisa dipakai masuk.
 */
interface PortalIdentityDirectory {

    /**
     * Semua pelanggan (lintas tenant) yang salah satu identitasnya cocok dengan [values].
     * Kosong = identitas tak dikenal. Beberapa hasil = identitas yang sama dipakai di lebih
     * dari satu ISP — sah, dan diselesaikan pemanggil dengan memverifikasi password.
     */
    fun findByValues(values: Collection<String>): List<PortalIdentityEntry>

    /**
     * Tulis ulang SELURUH identitas milik satu pelanggan (hapus lalu isi). Sengaja
     * mengganti-total alih-alih menambal: email/HP yang dikoreksi operator harus BERHENTI
     * bisa dipakai masuk, dan hanya penulisan-ulang yang menjamin itu.
     *
     * Nilai yang bentrok dengan pelanggan lain di tenant yang sama diabaikan diam-diam —
     * mis. satu keluarga berbagi satu nomor HP. Pelanggan kedua tetap bisa masuk lewat
     * username-nya sendiri, dan operator tak perlu diganggu soal ini.
     */
    fun replaceFor(tenantId: UUID, customerId: UUID, values: List<PortalIdentityValue>)
}

/** Satu pelanggan yang identitasnya cocok — cukup untuk memasang tenant context lalu lanjut. */
data class PortalIdentityEntry(
    val tenantId: UUID,
    val customerId: UUID,
)

/** Satu baris indeks identitas: bentuk kanonik + asal-usulnya. */
data class PortalIdentityValue(
    val kind: PortalIdentityKind,
    val value: String,
)

/**
 * Port persistence kode pemulihan password. Seperti refresh-token, SENGAJA bukan
 * tenant-aware: kode ditukar oleh orang yang belum login, jadi tenant belum terpasang.
 */
interface PortalPasswordResetRepository {

    fun save(reset: PortalPasswordReset): PortalPasswordReset

    fun findByCodeHash(codeHash: String): PortalPasswordReset?

    /** Cabut semua kode aktif milik pelanggan — dipanggil saat kode baru diterbitkan & setelah reset. */
    fun revokeActiveFor(customerId: UUID)

    /** Kapan kode terakhir diterbitkan untuk pelanggan ini; null = belum pernah. Untuk jeda kirim-ulang. */
    fun lastIssuedAtFor(customerId: UUID): Instant?
}
