package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseApprovalStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseCommandJdbc
import com.duluin.ftth.inventory.adapter.outbound.persistence.uuid
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MaterialApprovalInvalidationService(private val jdbc: WarehouseCommandJdbc, private val store: WarehouseApprovalStore,
    private val approvals: DurableApprovalService) : InventoryApprovalInvalidationApi {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun invalidateUnposted(documentIds: Set<UUID>, authority: AuthorityFence, cutover: TenantCutoverFence): Int {
        cutover.assertHeld()
        authority.assertHeld()
        documentIds.sortedBy(UUID::toString).forEach { store.source(it) }
        return documentIds.sortedBy(UUID::toString).sumOf { document ->
            val ids = jdbc.execute { sql -> sql.query("""SELECT id FROM inventory_approval WHERE tenant_id=? AND source_document_id=?
                AND status='PENDING' AND evaluation_snapshot IS NOT NULL ORDER BY id FOR UPDATE""", sql.tenant, document) { it.uuid("id") } }
            ids.forEach { approvals.terminate(store.get(it, true), WarehouseApprovalStatus.STALE) }
            ids.size
        }
    }
}
