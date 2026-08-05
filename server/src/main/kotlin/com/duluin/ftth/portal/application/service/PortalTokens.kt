package com.duluin.ftth.portal.application.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Utilitas token portal: refresh-token acak (disimpan sebagai hash SHA-256) + password
 * sementara yang enak dibaca. Cermin `Tokens` operator, dipisah agar portal tak menembus
 * batas module iam.
 */
internal object PortalTokens {
    private val random = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    /** Alfabet tanpa karakter ambigu (0/O, 1/l/I) agar password mudah dibacakan ke pelanggan. */
    private const val READABLE_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

    fun random(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return urlEncoder.encodeToString(bytes)
    }

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Password sementara 10 karakter dari alfabet non-ambigu — dibagikan operator sekali. */
    fun readablePassword(length: Int = 10): String =
        (1..length).map { READABLE_ALPHABET[random.nextInt(READABLE_ALPHABET.length)] }.joinToString("")
}
