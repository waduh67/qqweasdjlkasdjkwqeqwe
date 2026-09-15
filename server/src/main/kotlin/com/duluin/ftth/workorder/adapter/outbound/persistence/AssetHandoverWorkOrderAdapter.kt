package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderEvidenceQuery
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.UUID

@Component
@Transactional(propagation = Propagation.MANDATORY)
class AssetHandoverWorkOrderAdapter(private val authorities: CurrentAuthorityApi, private val users: IamApi,
    private val evidence: WorkOrderEvidenceQuery, private val entityManager: EntityManager) : AssetHandoverWorkOrderPort {
    override fun lock(binding: DeploymentBinding, authority: AuthorityFence): AssetHandoverWorkOrder {
        authority.assertHeld()
        val current = authorities.lockCurrent()
        if (binding.tenantId != TenantContext.tenantId() || binding.actorId != current.fence.identity.userId ||
            authority.identity != current.fence.identity || authority.epoch != current.fence.epoch) fail(WarehouseErrorCode.FORBIDDEN)
        if (!current.platformAdmin && !current.permissions.containsAll(setOf("workorder.order.field", "customer.onu.assign")))
            fail(WarehouseErrorCode.FORBIDDEN)
        val sender = users.usersByIds(setOf(binding.actorId)).singleOrNull { it.active && it.technician }
            ?: fail(WarehouseErrorCode.FORBIDDEN)
        return entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            connection.prepareStatement("""SELECT code,warehouse_revision,area_id FROM work_order
                WHERE tenant_id=? AND id=? AND customer_id=? AND type='PSB' AND status IN ('ASSIGNED','IN_PROGRESS','DONE')
                AND EXISTS(SELECT FROM work_order_assignee WHERE tenant_id=work_order.tenant_id
                    AND work_order_id=work_order.id AND technician_id=?) FOR UPDATE""").use { query ->
                query.setObject(1, binding.tenantId); query.setObject(2, binding.workOrderId)
                query.setObject(3, binding.customerId); query.setObject(4, binding.actorId)
                query.executeQuery().use { row ->
                    if (!row.next()) fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    val scope = current.areaScope
                    if (scope is AuthorityScope.Restricted && row.getObject("area_id", UUID::class.java) !in scope.ids)
                        fail(WarehouseErrorCode.FORBIDDEN)
                    AssetHandoverWorkOrder(row.getString("code"), row.getLong("warehouse_revision"), sender.name)
                }
            }
        }
    }

    override fun signature(workOrderId: UUID, evidenceId: UUID): AssetHandoverSignature {
        val snapshot = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            connection.prepareStatement("""SELECT signature.storage_key,signature.sha256,signature.signer_name,signature.receipt_at
                FROM wo_signature signature JOIN evidence_object_registry registry
                ON registry.tenant_id=signature.tenant_id AND registry.revision_id=signature.id
                WHERE signature.tenant_id=? AND signature.work_order_id=? AND signature.id=?
                AND signature.revision_state='COMMITTED' AND signature.purge_state='ACTIVE'
                AND registry.state='COMMITTED' AND registry.purge_state='ACTIVE'
                AND registry.expected_sha256=signature.sha256 AND registry.object_key=signature.storage_key
                FOR SHARE OF signature,registry""").use { query ->
                query.setObject(1, TenantContext.tenantId()); query.setObject(2, workOrderId); query.setObject(3, evidenceId)
                query.executeQuery().use { row ->
                    if (!row.next()) fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    AssetHandoverSignature(evidenceId, row.getString("storage_key"), row.getString("sha256"),
                        row.getString("signer_name"), row.getTimestamp("receipt_at").toInstant())
                }
            }
        }
        val content = evidence.downloadSignature(workOrderId)
        val digest = MessageDigest.getInstance("SHA-256").digest(content.bytes).joinToString("") { "%02x".format(it) }
        if (digest != snapshot.digest) fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return snapshot
    }

    private fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code, code.name))
}
