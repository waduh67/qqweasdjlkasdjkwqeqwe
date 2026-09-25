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
    override fun captureSources(cutover: TenantCutoverChangeFence, current: CurrentAuthority): List<ProvenanceSourceSnapshot> {
        cutover.assertHeld()
        current.fence.assertHeld()
        check(cutover.snapshot.tenantId == current.fence.identity.tenantId && cutover.snapshot.tenantId == TenantContext.tenantId())
        return entityManager.unwrap(Session::class.java).doReturningWork<List<ProvenanceSourceSnapshot>> { connection: java.sql.Connection ->
            val customerIds = connection.prepareStatement("SELECT DISTINCT customer_id FROM onu WHERE tenant_id=? AND warehouse_admission='LEGACY_UNRESOLVED'").use { query ->
                query.setObject(1, TenantContext.tenantId())
                query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
            }
            authorize(customerIds, current)
            connection.prepareStatement("""SELECT warehouse_provenance_case_id(tenant_id,source_table,source_id,source_hash) id,
                source_table,source_id,source_snapshot::text snapshot FROM customer_live_provenance_source WHERE tenant_id=? ORDER BY source_id""").use { query ->
                query.setObject(1, TenantContext.tenantId())
                query.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(ProvenanceSourceSnapshot(rows.getObject("id", UUID::class.java), rows.getString("source_table"),
                        rows.getObject("source_id", UUID::class.java), rows.getString("snapshot")))
                } }
            }
        }
    }

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
