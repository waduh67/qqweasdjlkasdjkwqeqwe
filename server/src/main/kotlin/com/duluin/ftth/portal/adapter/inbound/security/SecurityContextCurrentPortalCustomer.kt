package com.duluin.ftth.portal.adapter.inbound.security

import com.duluin.ftth.portal.security.CurrentPortalCustomer
import com.duluin.ftth.portal.security.PortalCustomer
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/** Implementasi [CurrentPortalCustomer] yang membaca principal dari Spring SecurityContext. */
@Component
class SecurityContextCurrentPortalCustomer : CurrentPortalCustomer {

    override fun currentOrNull(): PortalCustomer? =
        SecurityContextHolder.getContext().authentication?.principal as? PortalCustomer
}
