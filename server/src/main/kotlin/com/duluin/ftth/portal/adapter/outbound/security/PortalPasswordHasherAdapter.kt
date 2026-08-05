package com.duluin.ftth.portal.adapter.outbound.security

import com.duluin.ftth.portal.application.port.outbound.PortalPasswordHasher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * Hashing password portal memakai [PasswordEncoder] bersama (bean BCrypt di SecurityConfig).
 * Realm terpisah tetap boleh berbagi algoritma hashing — yang dipisah adalah penandatanganan
 * token, bukan cara menyimpan password.
 */
@Component
class PortalPasswordHasherAdapter(
    private val passwordEncoder: PasswordEncoder,
) : PortalPasswordHasher {

    override fun hash(rawPassword: String): String =
        passwordEncoder.encode(rawPassword) ?: error("PasswordEncoder mengembalikan null")

    override fun matches(rawPassword: String, passwordHash: String): Boolean =
        passwordEncoder.matches(rawPassword, passwordHash)
}
