package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.*
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
@Transactional(propagation = Propagation.MANDATORY)
class ProvenanceWorkOrderAdapter(private val entityManager: EntityManager) : InventoryProvenanceWorkOrderPort {
    override fun authorize(ids: Set<UUID>, current: CurrentAuthority): Map<UUID, ProvenanceWorkOrderReference> {
        current.fence.assertHeld()
        check(current.fence.identity.tenantId == TenantContext.tenantId())
        if (ids.isEmpty()) return emptyMap()
        return entityManager.unwrap(Session::class.java).doReturningWork<Map<UUID, ProvenanceWorkOrderReference>> { connection: java.sql.Connection ->
            connection.prepareStatement("SELECT id,code,customer_id,area_id FROM work_order WHERE tenant_id=? AND id=ANY(?) ORDER BY id FOR SHARE").use { query ->
                query.setObject(1, TenantContext.tenantId())
                query.setArray(2, connection.createArrayOf("uuid", ids.toTypedArray()))
                val result = query.executeQuery().use { rows -> buildMap {
                    while (rows.next()) {
                        val id = rows.getObject("id", UUID::class.java)
                        val area = rows.getObject("area_id", UUID::class.java)
                        val scope = current.areaScope
                        if (!current.platformAdmin && scope is AuthorityScope.Restricted && area !in scope.ids) denied()
                        put(id, ProvenanceWorkOrderReference(id, rows.getString("code"), rows.getObject("customer_id", UUID::class.java), area))
                    }
                } }
                if (!current.platformAdmin && result.keys != ids) denied()
                result
            }
        }
    }
    private fun denied(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.NOT_FOUND, "Provenance source unavailable"))
}
