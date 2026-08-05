package com.duluin.ftth.portal.adapter.inbound.security

import com.duluin.ftth.portal.security.PortalCustomer
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority

/**
 * Authentication yang tersimpan di SecurityContext setelah token portal tervalidasi.
 * Principal-nya [PortalCustomer]. Satu authority `ROLE_PORTAL_CUSTOMER` — cukup untuk
 * `@PreAuthorize("hasRole('PORTAL_CUSTOMER')")`; tak ada permukaan RBAC operator di sini.
 */
class PortalAuthenticationToken(
    private val customer: PortalCustomer,
) : AbstractAuthenticationToken(AUTHORITIES) {

    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): PortalCustomer = customer

    override fun getCredentials(): Any? = null

    override fun getName(): String = customer.login

    private companion object {
        val AUTHORITIES: Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_PORTAL_CUSTOMER"))
    }
}
