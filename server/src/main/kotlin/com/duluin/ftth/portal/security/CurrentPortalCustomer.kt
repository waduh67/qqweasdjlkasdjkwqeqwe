package com.duluin.ftth.portal.security

/**
 * Port untuk mengambil pelanggan portal yang sedang login. Diimplementasikan di adapter
 * (baca dari SecurityContext) sehingga lapisan application tetap bisa diuji tanpa Spring
 * Security. Sejalan dengan `CurrentUserProvider` operator, tapi untuk realm portal.
 */
interface CurrentPortalCustomer {

    fun currentOrNull(): PortalCustomer?

    fun current(): PortalCustomer =
        currentOrNull() ?: error("Tidak ada pelanggan portal terautentikasi pada context saat ini")
}
