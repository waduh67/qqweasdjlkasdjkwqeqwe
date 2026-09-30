package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.SiteAreaReference
import com.duluin.ftth.network.SiteReferenceApi
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class SiteReferencePersistence(private val manager: EntityManager) : SiteReferenceApi {
    override fun lock(id: UUID): SiteAreaReference? = query("AND id=? FOR SHARE", listOf(id)).singleOrNull()
    override fun lockForChange(id: UUID): SiteAreaReference? = query("AND id=? FOR UPDATE", listOf(id)).singleOrNull()
    override fun visibleAreas(scope: AuthorityScope): Map<UUID, UUID?> {
        if (scope is AuthorityScope.Restricted && scope.ids.isEmpty()) return emptyMap()
        val ids = if (scope is AuthorityScope.Restricted) scope.ids.sortedBy(UUID::toString) else emptyList()
        val predicate = if (scope is AuthorityScope.Restricted) "AND area_id IN (${ids.joinToString(",") { "?" }})" else ""
        return query("$predicate ORDER BY id FOR SHARE", ids).associate { it.id to it.areaId }
    }

    private fun query(predicate: String, values: List<UUID>): List<SiteAreaReference> =
        manager.unwrap(Session::class.java).doReturningWork { connection ->
            check(!connection.autoCommit)
            connection.prepareStatement("SELECT id,area_id FROM site WHERE tenant_id=? $predicate").use { statement ->
                statement.setObject(1, TenantContext.tenantId())
                values.forEachIndexed { index, id -> statement.setObject(index + 2, id) }
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(SiteAreaReference(rows.getObject("id", UUID::class.java), rows.getObject("area_id", UUID::class.java)))
                } }
            }
        }
}
