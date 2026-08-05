package com.duluin.ftth.portal.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.time.Instant
import java.util.UUID

/**
 * Kredensial login PORTAL milik seorang pelanggan — realm terpisah dari pengguna IAM
 * operator. Menyimpan password sebagai HASH (BCrypt), bukan nilai mentah. Satu pelanggan
 * paling banyak satu kredensial (invarian 1:1 ditegakkan di persistence + service).
 *
 * [login] dinormalkan lower-case & divalidasi bentuknya di domain agar aturan sama di
 * mana pun kredensial dibuat/diubah (operator provisioning maupun ganti-login mandiri).
 */
class PortalCredential private constructor(
    val id: UUID,
    val customerId: UUID,
    login: String,
    passwordHash: String,
    disabledAt: Instant?,
) {
    var login: String = login
        private set
    var passwordHash: String = passwordHash
        private set

    /** Non-null = dinonaktifkan operator; pelanggan tak bisa login. */
    var disabledAt: Instant? = disabledAt
        private set

    val active: Boolean get() = disabledAt == null

    fun changePassword(newHash: String) {
        passwordHash = newHash
    }

    fun changeLogin(newLogin: String) {
        login = normalizeLogin(newLogin)
    }

    fun disable(at: Instant = Instant.now()) {
        if (disabledAt == null) disabledAt = at
    }

    fun enable() {
        disabledAt = null
    }

    companion object {
        fun create(customerId: UUID, login: String, passwordHash: String): PortalCredential =
            PortalCredential(UuidV7.generate(), customerId, normalizeLogin(login), passwordHash, null)

        fun rehydrate(
            id: UUID,
            customerId: UUID,
            login: String,
            passwordHash: String,
            disabledAt: Instant?,
        ): PortalCredential = PortalCredential(id, customerId, login, passwordHash, disabledAt)

        /**
         * Normalisasi + validasi login: lower-case, 3..64 karakter, hanya huruf/angka dan
         * `. _ -`. Login diketik pelanggan saat masuk, jadi dijaga sederhana & tanpa spasi.
         */
        fun normalizeLogin(raw: String): String {
            val value = raw.trim().lowercase()
            require(value.length in 3..64) { "Login portal harus 3..64 karakter" }
            require(value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) {
                "Login portal hanya boleh huruf, angka, titik, garis bawah, atau strip"
            }
            return value
        }
    }
}
