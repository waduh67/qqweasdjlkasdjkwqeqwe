package com.duluin.ftth.fulfillment

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
class FulfillmentMigrationAdapter(private val entityManager: EntityManager,
    private val workOrders: InventoryProvenanceWorkOrderPort) : InventoryMigrationEffectsPort {
    override fun captureSources(cutover: TenantCutoverChangeFence, current: CurrentAuthority): List<ProvenanceSourceSnapshot> {
        cutover.assertHeld()
        current.fence.assertHeld()
        check(cutover.snapshot.tenantId == current.fence.identity.tenantId && cutover.snapshot.tenantId == TenantContext.tenantId())
        return entityManager.unwrap(Session::class.java).doReturningWork<List<ProvenanceSourceSnapshot>> { connection: java.sql.Connection ->
            val ids = connection.prepareStatement("""SELECT DISTINCT (source_snapshot->>'workOrderId')::uuid FROM fulfillment_live_provenance_source
                WHERE tenant_id=? AND source_snapshot->>'workOrderId' IS NOT NULL""").use { query ->
                query.setObject(1, TenantContext.tenantId())
                query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
            }
            workOrders.authorize(ids, current)
            connection.prepareStatement("""SELECT warehouse_provenance_case_id(tenant_id,source_table,source_id,source_hash) id,
                source_table,source_id,source_snapshot::text snapshot FROM fulfillment_live_provenance_source WHERE tenant_id=? ORDER BY source_table,source_id""").use { query ->
                query.setObject(1, TenantContext.tenantId())
                query.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(ProvenanceSourceSnapshot(rows.getObject("id", UUID::class.java), rows.getString("source_table"),
                        rows.getObject("source_id", UUID::class.java), rows.getString("snapshot")))
                } }
            }
        }
    }
}
