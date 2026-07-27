package com.duluin.ftth.vpn.application.service

import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * Generator password acak untuk peer VPN. Efek samping (keacakan kriptografis) sengaja
 * diletakkan di lapisan application agar domain tetap murni/deterministik.
 */
@Component
class PasswordGenerator {

    private val random = SecureRandom()

    /** ~20 karakter alfanumerik url-safe (tanpa simbol yang bisa merepotkan di config). */
    fun generate(length: Int = 20): String =
        buildString(length) {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

    private companion object {
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
