package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.InventoryWorkOrderReadPort
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class WorkOrderInventoryReadAdapter(private val manager: EntityManager) : InventoryWorkOrderReadPort {
    override fun visibleWorkOrderIds(scope: AuthorityScope): Set<UUID> =
        manager.unwrap(Session::class.java).doReturningWork { connection ->
            check(!connection.autoCommit)
            val areas = when (scope) {
                AuthorityScope.Unrestricted -> null
                is AuthorityScope.Restricted -> connection.createArrayOf("uuid", scope.ids.toTypedArray())
            }
            connection.prepareStatement("SELECT id FROM work_order WHERE tenant_id=? AND (?::uuid[] IS NULL OR area_id=ANY(?::uuid[])) ORDER BY id FOR SHARE").use { statement ->
                statement.queryTimeout = 20
                statement.setObject(1, TenantContext.tenantId())
                statement.setArray(2, areas)
                statement.setArray(3, areas)
                statement.executeQuery().use { rows -> buildSet {
                    while (rows.next()) add(rows.getObject("id", UUID::class.java))
                } }
            }
        }
}
