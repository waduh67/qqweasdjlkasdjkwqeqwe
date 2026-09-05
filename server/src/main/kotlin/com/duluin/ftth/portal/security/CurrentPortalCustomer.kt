package com.duluin.ftth.portal.security

import com.duluin.ftth.portal.PortalCustomerSession

/**
 * Port untuk mengambil pelanggan portal yang sedang login. Diimplementasikan di adapter
 * (baca dari SecurityContext) sehingga lapisan application tetap bisa diuji tanpa Spring
 * Security. Sejalan dengan `CurrentUserProvider` operator, tapi untuk realm portal.
 */
interface CurrentPortalCustomer : PortalCustomerSession {

    fun currentOrNull(): PortalCustomer?

    fun current(): PortalCustomer =
        currentOrNull() ?: error("Tidak ada pelanggan portal terautentikasi pada context saat ini")

    override fun currentCustomerId() = current().customerId
}
