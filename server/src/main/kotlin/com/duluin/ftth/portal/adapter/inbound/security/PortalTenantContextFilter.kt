package com.duluin.ftth.portal.adapter.inbound.security

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.portal.security.PortalCustomer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Setelah token portal tervalidasi, memasang tenant dari [PortalCustomer] ke
 * [TenantContext] untuk seluruh durasi request lalu membersihkannya — dengan begitu
 * Hibernate & RLS otomatis men-scope semua query ke tenant pelanggan. Cermin
 * `TenantContextFilter` operator, tapi khusus principal portal (rantai keamanan sendiri).
 */
class PortalTenantContextFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val customer = SecurityContextHolder.getContext().authentication?.principal as? PortalCustomer
        if (customer != null) TenantContext.set(customer.tenantId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            TenantContext.clear()
        }
    }
}
