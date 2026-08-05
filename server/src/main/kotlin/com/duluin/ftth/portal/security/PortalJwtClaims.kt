package com.duluin.ftth.portal.security

/**
 * Nama klaim access-token PORTAL — kontrak antara penerbit (adapter outbound security)
 * dan pemverifikasi (rantai keamanan portal). Dipisah dari `JwtClaims` operator agar
 * kedua realm tak berbagi bentuk token. [TOKEN_USE] adalah diskriminator tambahan:
 * meski secret sudah beda (isolasi kripto), klaim `use=portal` membuat penyalahgunaan
 * token operator sebagai token portal gagal secara eksplisit.
 */
object PortalJwtClaims {
    const val TENANT_ID = "tid"
    const val LOGIN = "login"
    const val NAME = "name"
    const val TOKEN_USE = "use"
    const val TOKEN_USE_PORTAL = "portal"
}
