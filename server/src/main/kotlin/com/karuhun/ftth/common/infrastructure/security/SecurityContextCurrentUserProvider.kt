package com.karuhun.ftth.common.infrastructure.security

import com.karuhun.ftth.common.security.AuthenticatedUser
import com.karuhun.ftth.common.security.CurrentUserProvider
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/** Implementasi [CurrentUserProvider] yang membaca dari Spring SecurityContext. */
@Component
class SecurityContextCurrentUserProvider : CurrentUserProvider {

    override fun currentOrNull(): AuthenticatedUser? =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedUser
}
