package com.duluin.ftth.hotspot.application.service

import org.springframework.stereotype.Component
import java.security.SecureRandom

@Component
class VoucherCredentialGenerator {
    private val random = SecureRandom()

    fun username(): String = "VCH-" + token(10, CODE_ALPHABET)

    fun password(): String = token(12, PASSWORD_ALPHABET)

    private fun token(length: Int, alphabet: String): String =
        buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }

    private companion object {
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val PASSWORD_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
