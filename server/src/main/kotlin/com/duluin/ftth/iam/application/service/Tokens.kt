package com.duluin.ftth.iam.application.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Utilitas refresh-token: pembangkitan acak & hashing SHA-256 (disimpan sebagai hash). */
internal object Tokens {
    private val random = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun random(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return urlEncoder.encodeToString(bytes)
    }

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
