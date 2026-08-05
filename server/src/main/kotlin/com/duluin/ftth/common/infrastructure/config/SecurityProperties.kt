package com.duluin.ftth.common.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.security.MessageDigest
import java.time.Duration

@ConfigurationProperties(prefix = "ftth.security")
data class SecurityProperties(
    /** Secret HMAC untuk menandatangani access-token. Minimal 32 byte (HS256). */
    val jwtSecret: String,
    /**
     * Secret untuk enkripsi kredensial perangkat (AES-GCM). Sengaja terpisah dari
     * [jwtSecret]: satu kunci satu tujuan, sehingga rotasi kunci token tidak
     * membuat seluruh kredensial SNMP mendadak tak terbaca.
     */
    val encryptionSecret: String,
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
    val refreshTokenTtl: Duration = Duration.ofDays(30),
    /**
     * Secret HMAC untuk token PORTAL pelanggan — realm terpisah dari operator. SENGAJA
     * beda dari [jwtSecret]: token operator dan token portal tak boleh saling divalidasi
     * (token operator gagal di decoder portal, dan sebaliknya). Bila TIDAK dikonfigurasi,
     * diturunkan deterministik dari [jwtSecret] lewat [effectivePortalJwtSecret] — stabil
     * lintas restart, tetap ≥32 byte, dan pasti berbeda — sehingga deployment lama tak
     * perlu env baru. Boleh dioverride eksplisit via `ftth.security.portal-jwt-secret`.
     */
    val portalJwtSecret: String? = null,
) {
    init {
        require(jwtSecret.toByteArray().size >= 32) {
            "ftth.security.jwt-secret harus >= 32 byte untuk HS256"
        }
        require(encryptionSecret.toByteArray().size >= 32) {
            "ftth.security.encryption-secret harus >= 32 byte"
        }
        require(jwtSecret != encryptionSecret) {
            "ftth.security.encryption-secret tidak boleh sama dengan jwt-secret"
        }
        portalJwtSecret?.let {
            require(it.toByteArray().size >= 32) {
                "ftth.security.portal-jwt-secret harus >= 32 byte untuk HS256"
            }
            require(it != jwtSecret) {
                "ftth.security.portal-jwt-secret tidak boleh sama dengan jwt-secret (isolasi realm portal)"
            }
        }
    }

    /**
     * Secret HS256 efektif untuk token portal: yang dikonfigurasi bila ada, jika tidak
     * turunan deterministik dari [jwtSecret] (SHA-256 dari `<jwtSecret>:portal` → 64 hex
     * char = 64 byte, ≥32 dan pasti ≠ jwtSecret). Diturunkan, bukan diacak, agar tetap
     * sama setelah restart tanpa menyimpan state.
     */
    val effectivePortalJwtSecret: String
        get() = portalJwtSecret ?: run {
            val digest = MessageDigest.getInstance("SHA-256").digest("$jwtSecret:portal".toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        }
}
