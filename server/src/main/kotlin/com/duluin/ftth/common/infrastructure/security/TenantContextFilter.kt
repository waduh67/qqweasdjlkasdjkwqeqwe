package com.duluin.ftth.common.infrastructure.security

import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.tenant.TenantContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Setelah JWT tervalidasi, memasang tenant dari principal ke [TenantContext]
 * untuk seluruh durasi request lalu membersihkannya — dengan begitu Hibernate &
 * RLS otomatis men-scope semua query ke tenant pengguna. Dibersihkan di `finally`
 * agar tidak bocor ke request lain yang memakai thread pool yang sama.
 */
class TenantContextFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val user = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedUser
        if (user != null) TenantContext.set(user.tenantId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            TenantContext.clear()
        }
    }
}
