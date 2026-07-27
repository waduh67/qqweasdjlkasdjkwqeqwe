package com.duluin.ftth.vpn.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Kredensial node per hub: dipakai installer + skrip callback OpenVPN di VPS untuk memanggil
 * balik aplikasi (verifikasi user/pass, minta IP overlay tetap). Disimpan di tabel TANPA RLS
 * agar bisa di-resolve SEBELUM tenant diketahui — sama seperti API key collector.
 *
 * Hanya [tokenHash] (SHA-256) yang disimpan; token mentah hanya ditampilkan SEKALI saat
 * diterbitkan/dirotasi. [tokenHint] (akhiran) hanya untuk tampilan UI. [tenantId] disimpan
 * agar [TenantContext] bisa dipasang sebelum membaca `vpn_server` yang ber-RLS.
 */
class VpnNodeToken private constructor(
    val id: UUID,
    val serverId: UUID,
    val tenantId: UUID,
    val tokenHash: String,
    val tokenHint: String,
) {
    companion object {
        /** Awalan token supaya mudah dikenali (cermin `ftthc_` collector). */
        const val PREFIX = "ftthv_"
        private const val TOKEN_BYTES = 32
        private const val HINT_LENGTH = 6

        /** Terbitkan token baru untuk sebuah hub; mengembalikan entitas + token MENTAH (sekali tampil). */
        fun issue(serverId: UUID, tenantId: UUID): Pair<VpnNodeToken, String> {
            val raw = generate()
            val token = VpnNodeToken(
                id = UuidV7.generate(),
                serverId = serverId,
                tenantId = tenantId,
                tokenHash = hash(raw),
                tokenHint = raw.takeLast(HINT_LENGTH),
            )
            return token to raw
        }

        fun rehydrate(id: UUID, serverId: UUID, tenantId: UUID, tokenHash: String, tokenHint: String): VpnNodeToken =
            VpnNodeToken(id, serverId, tenantId, tokenHash, tokenHint)

        fun generate(): String {
            val bytes = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** Hash tetap (SHA-256 hex), bukan bcrypt: dicek tiap koneksi & entropinya sudah 256-bit. */
        fun hash(rawToken: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(rawToken.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
