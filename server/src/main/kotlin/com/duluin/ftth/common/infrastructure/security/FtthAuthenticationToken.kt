package com.duluin.ftth.common.infrastructure.security

import com.duluin.ftth.common.security.AuthenticatedUser
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority

/**
 * Authentication yang tersimpan di SecurityContext setelah JWT tervalidasi.
 * Principal-nya adalah [AuthenticatedUser] domain sehingga controller & service
 * bekerja dengan tipe milik aplikasi, bukan `Jwt` mentah.
 */
class FtthAuthenticationToken(
    private val user: AuthenticatedUser,
) : AbstractAuthenticationToken(authoritiesOf(user)) {

    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): AuthenticatedUser = user

    override fun getCredentials(): Any? = null

    override fun getName(): String = user.email

    companion object {
        private fun authoritiesOf(user: AuthenticatedUser): Collection<GrantedAuthority> {
            val authorities = user.permissions.map { SimpleGrantedAuthority(it) }.toMutableList<GrantedAuthority>()
            if (user.platformAdmin) authorities += SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")
            return authorities
        }
    }
}
