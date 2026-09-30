package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.workorder.*
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class WorkOrderAssetWorkbenchAdapter(private val authorities: CurrentAuthorityApi, private val users: IamApi,
    private val contexts: WorkOrderMaterialContextApi, private val entityManager: EntityManager) : WorkOrderAssetWorkbenchApi {
    override fun jobs(customerId: UUID, page: WarehousePageRequest, authority: AuthorityFence): WarehousePage<AssetWorkOrderChoice> {
        val current = current(authority)
        if (page.page < 0 || page.size !in 1..100) fail(WarehouseErrorCode.MALFORMED_REQUEST)
        val (ids, count) = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            val areas = (current.areaScope as? AuthorityScope.Restricted)?.ids?.toTypedArray()
            val predicate = """tenant_id=? AND customer_id=? AND (?::uuid[] IS NULL OR area_id=ANY(?::uuid[]))
                AND type IN ('PSB','MIGRATION','DISMANTLE','REPAIR') AND status IN ('ASSIGNED','IN_PROGRESS','DONE')
                AND EXISTS(SELECT FROM work_order_assignee assigned WHERE assigned.tenant_id=work_order.tenant_id
                    AND assigned.work_order_id=work_order.id AND assigned.technician_id=?)"""
            fun query(select: String, suffix: String, rows: (java.sql.ResultSet) -> Unit) {
                connection.prepareStatement("SELECT $select FROM work_order WHERE $predicate $suffix").use { statement ->
                    statement.setObject(1, TenantContext.tenantId()); statement.setObject(2, customerId)
                    statement.setArray(3, areas?.let { connection.createArrayOf("uuid", it) }); statement.setArray(4, areas?.let { connection.createArrayOf("uuid", it) })
                    statement.setObject(5, current.fence.identity.userId); statement.executeQuery().use(rows)
                }
            }
            var total = 0L
            query("count(*)", "") { it.next(); total = it.getLong(1) }
            val selected = mutableListOf<UUID>()
            query("id", "ORDER BY updated_at DESC,id LIMIT ${page.size} OFFSET ${page.page.toLong() * page.size}") { rows ->
                while (rows.next()) selected += rows.getObject(1, UUID::class.java)
            }
            selected to total
        }
        return WarehousePage(ids.map { job(customerId, it, authority) }, page.page, page.size, count)
    }
    override fun job(customerId: UUID, id: UUID, authority: AuthorityFence): AssetWorkOrderChoice {
        val current = current(authority)
        val context = contexts.lockForCustody(id, authority)
        val material = context.material
        if (material.customerId != customerId || current.fence.identity.userId !in material.activeAssigneeIds ||
            material.workType !in setOf("PSB", "MIGRATION", "DISMANTLE", "REPAIR") || context.technicalState !in setOf("ASSIGNED", "IN_PROGRESS", "DONE")) fail(WarehouseErrorCode.NOT_FOUND)
        val signature = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            connection.prepareStatement("""SELECT signature.id,signature.signer_name,signature.receipt_at FROM wo_signature signature
                JOIN evidence_object_registry registry ON registry.tenant_id=signature.tenant_id AND registry.revision_id=signature.id
                WHERE signature.tenant_id=? AND signature.work_order_id=? AND signature.revision_state='COMMITTED' AND signature.purge_state='ACTIVE'
                    AND registry.state='COMMITTED' AND registry.purge_state='ACTIVE' AND registry.expected_sha256=signature.sha256
                    AND registry.object_key=signature.storage_key ORDER BY signature.receipt_at DESC,signature.id DESC LIMIT 1""").use { statement ->
                statement.setObject(1, TenantContext.tenantId()); statement.setObject(2, id)
                statement.executeQuery().use { rows -> if (rows.next()) AssetWorkOrderSignature(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getTimestamp(3).toInstant()) else null }
            }
        }
        return AssetWorkOrderChoice(id, material.code, customerId, material.workType, context.technicalState, material.workOrderRevision, signature)
    }
    private fun current(authority: AuthorityFence): com.duluin.ftth.iam.CurrentAuthority {
        authority.assertHeld()
        val current = authorities.lockCurrent()
        if (authority.identity != current.fence.identity || authority.epoch != current.fence.epoch) fail(WarehouseErrorCode.STALE_AUTHORITY)
        if (!current.platformAdmin && !current.permissions.containsAll(setOf("customer.onu.assign", "workorder.order.field"))) fail(WarehouseErrorCode.FORBIDDEN)
        if (users.usersByIds(setOf(current.fence.identity.userId)).none { it.active && it.technician }) fail(WarehouseErrorCode.FORBIDDEN)
        return current
    }
    private fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code, code.name))
}
