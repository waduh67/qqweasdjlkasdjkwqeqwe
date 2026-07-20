package com.duluin.ftth.common.infrastructure.persistence.multitenancy

import com.duluin.ftth.common.tenant.TenantContext
import org.hibernate.context.spi.CurrentTenantIdentifierResolver
import java.util.UUID

/**
 * Meng-resolve tenant aktif dari [TenantContext] setiap kali Hibernate membuka
 * session.
 *
 * Saat context kosong, mengembalikan sentinel [ROOT] (bukan null — Hibernate
 * melarang null). Efeknya deny-by-default: query entity tenant-scoped tidak
 * cocok dengan baris mana pun, dan RLS di DB juga tidak meloloskan apa-apa.
 */
class TenantIdentifierResolver : CurrentTenantIdentifierResolver<UUID> {

    override fun resolveCurrentTenantIdentifier(): UUID =
        TenantContext.tenantIdOrNull() ?: ROOT

    override fun validateExistingCurrentSessions(): Boolean = false

    companion object {
        /** Tenant sentinel "tidak ada" — UUID nol, tidak akan pernah cocok dengan tenant nyata. */
        val ROOT: UUID = UUID(0, 0)
    }
}
