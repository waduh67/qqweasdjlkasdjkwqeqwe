package com.duluin.ftth.iam.adapter.outbound.security

import com.duluin.ftth.iam.application.port.outbound.PasswordHasher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/** Implementasi [PasswordHasher] memakai [PasswordEncoder] (BCrypt) dari Spring Security. */
@Component
class BCryptPasswordHasher(
    private val passwordEncoder: PasswordEncoder,
) : PasswordHasher {

    override fun hash(rawPassword: String): String =
        passwordEncoder.encode(rawPassword) ?: error("PasswordEncoder mengembalikan null")

    override fun matches(rawPassword: String, passwordHash: String): Boolean =
        passwordEncoder.matches(rawPassword, passwordHash)
}
