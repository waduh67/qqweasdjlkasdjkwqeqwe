package com.duluin.ftth.common.security

/**
 * Nama klaim access-token — kontrak yang disepakati bersama antara penerbit
 * token (module iam) dan pemverifikasi (security config di common). Disatukan
 * di sini agar tidak ada string ajaib yang gampang miss-match.
 */
object JwtClaims {
    const val TENANT_ID = "tid"
    const val EMAIL = "email"
    const val NAME = "name"
    const val PLATFORM_ADMIN = "padm"
    const val PERMISSIONS = "perms"
    const val AREAS = "areas"
}
