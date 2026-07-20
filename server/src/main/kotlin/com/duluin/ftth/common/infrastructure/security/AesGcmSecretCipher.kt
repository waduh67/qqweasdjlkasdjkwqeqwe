package com.duluin.ftth.common.infrastructure.security

import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.common.security.SecretCipher
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM. GCM dipilih karena authenticated encryption: ciphertext yang
 * diubah-ubah di database akan ditolak saat dekripsi, bukan diam-diam
 * menghasilkan sampah.
 *
 * Format tersimpan: `v1:` + base64(IV ‖ ciphertext ‖ tag). Prefiks versi menjaga
 * agar rotasi algoritma nanti bisa mendekripsi data lama.
 */
@Component
class AesGcmSecretCipher(properties: SecurityProperties) : SecretCipher {

    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(properties.encryptionSecret.toByteArray()),
        "AES",
    )
    private val random = SecureRandom()

    override fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        val sealed = cipher.doFinal(plaintext.toByteArray())
        return PREFIX + Base64.getEncoder().encodeToString(iv + sealed)
    }

    override fun decrypt(ciphertext: String): String {
        require(ciphertext.startsWith(PREFIX)) { "Format ciphertext tidak dikenal" }
        val raw = Base64.getDecoder().decode(ciphertext.removePrefix(PREFIX))
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        }
        return String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES))
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
