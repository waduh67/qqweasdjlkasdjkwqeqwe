package com.duluin.ftth.common.security

/**
 * Port untuk mengambil pengguna yang sedang login. Diimplementasikan di
 * infrastructure (baca dari SecurityContext) sehingga lapisan application tetap
 * bisa diuji tanpa Spring Security.
 */
interface CurrentUserProvider {

    fun currentOrNull(): AuthenticatedUser?

    fun current(): AuthenticatedUser =
        currentOrNull() ?: error("Tidak ada pengguna terautentikasi pada context saat ini")
}
