package com.duluin.ftth.iam.adapter.outbound.security

import com.duluin.ftth.iam.application.port.outbound.TotpEngine
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * TOTP RFC 6238 dengan parameter bawaan yang dipakai semua aplikasi autentikator arus
 * utama (Google Authenticator, Aegis, 1Password, Authy): HMAC-SHA1, langkah 30 detik,
 * 6 digit. Ditulis tangan, bukan menarik pustaka: seluruh algoritmanya di bawah 60 baris
 * dan punya vektor uji resmi — menambah dependensi justru menambah permukaan yang harus
 * diikuti pembaruannya bertahun-tahun ke depan.
 *
 * SHA-1 di sini bukan kelalaian: HMAC-SHA1 tidak tersentuh serangan tabrakan SHA-1, dan
 * varian SHA-256 tak dipakai sebagian aplikasi autentikator populer — memilihnya berarti
 * sebagian operator memindai QR lalu mendapat kode yang selalu ditolak.
 */
@Component
class Rfc6238TotpEngine : TotpEngine {

    private val random = SecureRandom()

    override fun newSecret(): String = Base32.encode(ByteArray(SECRET_BYTES).also(random::nextBytes))

    override fun verify(secret: String, code: String, at: Instant): Long? {
        val digits = code.filter(Char::isDigit)
        if (digits.length != DIGITS) return null
        val key = runCatching { Base32.decode(secret) }.getOrNull() ?: return null
        val current = at.epochSecond / STEP_SECONDS

        // Jendela ±1 langkah: jam ponsel yang meleset beberapa detik adalah keluhan
        // dukungan paling sering di fitur ini, dan menerima langkah tetangga jauh lebih
        // murah daripada mengajari setiap operator menyetel NTP.
        for (offset in -WINDOW..WINDOW) {
            val step = current + offset
            if (constantTimeEquals(codeFor(key, step), digits)) return step
        }
        return null
    }

    override fun provisioningUri(secret: String, account: String, issuer: String): String {
        val label = encode("$issuer:$account")
        return "otpauth://totp/$label?secret=$secret&issuer=${encode(issuer)}" +
            "&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
    }

    /**
     * Kode untuk satu langkah waktu. `internal`, bukan private: uji memakainya sebagai
     * "sisi aplikasi autentikator" — tanpa itu, satu-satunya cara menguji verifikasi
     * adalah menyalin ulang seluruh algoritma di berkas uji, yang berarti dua salinan
     * yang bisa sama-sama salah dengan cara yang sama.
     */
    internal fun codeFor(key: ByteArray, step: Long): String {
        val message = ByteArray(Long.SIZE_BYTES)
        var value = step
        for (i in message.indices.reversed()) {
            message[i] = (value and 0xFF).toByte()
            value = value shr Byte.SIZE_BITS
        }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val hash = mac.doFinal(message)

        // Dynamic truncation (RFC 4226 §5.3): 4 bit terakhir menunjuk offset kata 31-bit
        // yang dipakai — supaya digit yang diambil tak selalu dari posisi yang sama.
        val offset = (hash[hash.size - 1].toInt() and 0x0F)
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        val modulus = 10.0.pow(DIGITS).toInt()
        return (binary % modulus).toString().padStart(DIGITS, '0')
    }

    /**
     * Perbandingan berwaktu tetap. Kode cuma 6 digit: dengan perbandingan yang berhenti
     * di karakter pertama yang beda, selisih waktu jawaban bisa dipakai menebak digit
     * per digit alih-alih menebak sejuta kemungkinan.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /**
     * `URLEncoder` memakai aturan formulir HTML: spasi jadi `+`. Aplikasi autentikator
     * membaca ini sebagai URI, bukan formulir — tanpa penggantian ini nama penerbit
     * tampil "NetOps+Console" di layar ponsel setiap operator.
     */
    private fun encode(raw: String): String =
        URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20")

    private companion object {
        const val SECRET_BYTES = 20
        const val STEP_SECONDS = 30L
        const val DIGITS = 6
        const val WINDOW = 1
    }
}

/**
 * Base32 (RFC 4648) tanpa padding — abjad yang dipakai `otpauth://`. Ditulis di sini
 * karena JDK tak menyediakannya (hanya Base64) dan yang dibutuhkan cuma dua fungsi.
 */
internal object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BITS_PER_CHAR = 5
    private const val MASK = 0x1F

    fun encode(data: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        data.forEach { byte ->
            buffer = (buffer shl Byte.SIZE_BITS) or (byte.toInt() and 0xFF)
            bits += Byte.SIZE_BITS
            while (bits >= BITS_PER_CHAR) {
                bits -= BITS_PER_CHAR
                out.append(ALPHABET[(buffer shr bits) and MASK])
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (BITS_PER_CHAR - bits)) and MASK])
        return out.toString()
    }

    fun decode(encoded: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        encoded.uppercase().forEach { char ->
            if (char == '=' || char == ' ' || char == '-') return@forEach
            val index = ALPHABET.indexOf(char)
            require(index >= 0) { "Karakter Base32 tidak valid: $char" }
            buffer = (buffer shl BITS_PER_CHAR) or index
            bits += BITS_PER_CHAR
            if (bits >= Byte.SIZE_BITS) {
                bits -= Byte.SIZE_BITS
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
