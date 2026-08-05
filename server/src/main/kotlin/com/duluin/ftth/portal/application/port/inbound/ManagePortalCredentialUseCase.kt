package com.duluin.ftth.portal.application.port.inbound

import java.util.UUID

/**
 * Kelola kredensial portal seorang pelanggan. Dua sisi pemakai, dijaga di controller
 * berbeda:
 *  - OPERATOR (rantai utama, izin `portal.credential.manage`): [provisionFor],
 *    [resetPassword], [setEnabled], [summaryFor] — menyiapkan/mereset akses pelanggan.
 *  - PELANGGAN sendiri (rantai portal): [changeOwnPassword] — ganti password mandiri.
 */
interface ManagePortalCredentialUseCase {

    /** Ringkasan kredensial pelanggan untuk panel operator; `null` bila belum dibuat. */
    fun summaryFor(customerId: UUID): PortalCredentialSummary?

    /**
     * Buat (atau perbarui) kredensial pelanggan. [login] kosong = pakai kode pelanggan.
     * [password] kosong = server men-generate password sementara sekali-tampil. Idempotent:
     * bila kredensial sudah ada, login/password yang diberikan menimpa yang lama.
     */
    fun provisionFor(customerId: UUID, login: String?, password: String?): PortalCredentialProvisioned

    /** Reset password pelanggan ke [newPassword] kosong = generate sementara. */
    fun resetPassword(customerId: UUID, newPassword: String?): PortalCredentialProvisioned

    /** Aktif/nonaktifkan login pelanggan tanpa menghapus kredensial. */
    fun setEnabled(customerId: UUID, enabled: Boolean): PortalCredentialSummary

    /** Ganti password mandiri oleh pelanggan yang sedang login (verifikasi password lama). */
    fun changeOwnPassword(currentPassword: String, newPassword: String)
}

/** Potret kredensial portal pelanggan untuk operator (tanpa hash/password). */
data class PortalCredentialSummary(
    val customerId: UUID,
    val login: String,
    val active: Boolean,
)

/**
 * Hasil provisi/reset. [temporaryPassword] terisi HANYA saat server men-generate password
 * (operator wajib membagikannya sekali ke pelanggan); `null` bila operator menetapkan
 * password sendiri.
 */
data class PortalCredentialProvisioned(
    val customerId: UUID,
    val login: String,
    val active: Boolean,
    val temporaryPassword: String?,
)
