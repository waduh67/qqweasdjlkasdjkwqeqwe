package com.duluin.ftth.customer.adapter.outbound.persistence

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
class ProvenanceCustomerAdapter(private val entityManager: EntityManager) : InventoryProvenanceCustomerPort {
    override fun authorize(customerIds: Set<UUID>, current: CurrentAuthority): Map<UUID, ProvenanceCustomerReference> {
        current.fence.assertHeld()
        check(current.fence.identity.tenantId == TenantContext.tenantId())
        if (customerIds.isEmpty()) return emptyMap()
        return entityManager.unwrap(Session::class.java).doReturningWork<Map<UUID, ProvenanceCustomerReference>> { connection: java.sql.Connection ->
            connection.prepareStatement("SELECT id,name,area_id FROM customer WHERE tenant_id=? AND id=ANY(?) ORDER BY id FOR SHARE").use { query ->
                query.setObject(1, TenantContext.tenantId())
                query.setArray(2, connection.createArrayOf("uuid", customerIds.toTypedArray()))
                val references = query.executeQuery().use { rows -> buildMap {
                    while (rows.next()) {
                        val area = rows.getObject("area_id", UUID::class.java)
                        val scope = current.areaScope
                        if (!current.platformAdmin && scope is AuthorityScope.Restricted && area !in scope.ids) denied()
                        val id = rows.getObject("id", UUID::class.java)
                        put(id, ProvenanceCustomerReference(id, rows.getString("name"), area))
                    }
                } }
                // Missing legacy links remain reportable by platform operators, never assignable.
                if (!current.platformAdmin && references.keys != customerIds) denied()
                references
            }
        }
    }

    private fun denied(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.NOT_FOUND, "Provenance source unavailable"))
}
