package com.duluin.ftth.common.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
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
    }
}
