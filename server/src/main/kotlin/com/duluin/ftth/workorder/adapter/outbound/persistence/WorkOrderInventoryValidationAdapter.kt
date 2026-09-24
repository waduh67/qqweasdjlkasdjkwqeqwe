package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class WorkOrderInventoryValidationAdapter(private val entityManager: EntityManager,
    private val authority: com.duluin.ftth.iam.DeliveryAuthorityApi, private val users: IamApi) : InventoryWorkOrderValidationPort {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockAndValidate(context: DeploymentValidationContext): ValidatedWorkOrderContext {
        context.cutoverFence.assertHeld()
        context.authorityFence.assertHeld()
        val binding = context.binding
        val current = authority.lockActor(context.authorityFence.identity)
        if (binding.tenantId != TenantContext.tenantId() || binding.actorId != current.fence.identity.userId ||
            current.fence.identity != context.authorityFence.identity) fail(WarehouseErrorCode.FORBIDDEN)
        if (!current.platformAdmin && !current.permissions.containsAll(setOf("workorder.order.field", "customer.onu.assign")))
            fail(WarehouseErrorCode.FORBIDDEN)
        if (binding.authorityEpoch != current.fence.epoch || current.fence.epoch != context.authorityFence.epoch)
            fail(WarehouseErrorCode.STALE_AUTHORITY)
        if (binding.cutoverEpoch != context.cutoverFence.snapshot.epoch) fail(WarehouseErrorCode.STALE_CUTOVER)
        val workType = when (binding.purpose) {
            DeploymentPurpose.INSTALL -> "PSB"
            DeploymentPurpose.REPLACE -> "MIGRATION"
            DeploymentPurpose.RETURN_CUSTOMER_RMA -> "REPAIR"
            DeploymentPurpose.REMOVE -> fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        }
        entityManager.unwrap(Session::class.java).doWork { connection ->
            connection.prepareStatement("SELECT customer_id,area_id,type,status,warehouse_revision FROM work_order WHERE tenant_id=? AND id=? FOR UPDATE").use { query ->
                query.setObject(1, binding.tenantId); query.setObject(2, binding.workOrderId)
                query.executeQuery().use { row ->
                    if (!row.next()) fail(WarehouseErrorCode.NOT_FOUND)
                    if (row.getObject("customer_id", UUID::class.java) != binding.customerId || row.getString("type") != workType ||
                        row.getString("status") !in setOf("ASSIGNED", "IN_PROGRESS")) fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    if (row.getLong("warehouse_revision") != binding.revisions.workOrderRevision) fail(WarehouseErrorCode.STALE_REVISION)
                    val scope = current.areaScope
                    if (scope is AuthorityScope.Restricted && row.getObject("area_id", UUID::class.java) !in scope.ids) fail(WarehouseErrorCode.FORBIDDEN)
                }
            }
            connection.prepareStatement("SELECT technician_id FROM work_order_assignee WHERE tenant_id=? AND work_order_id=? AND technician_id=?").use { query ->
                query.setObject(1, binding.tenantId); query.setObject(2, binding.workOrderId); query.setObject(3, binding.actorId)
                query.executeQuery().use { row -> if (!row.next()) fail(WarehouseErrorCode.WRONG_CUSTODIAN) }
            }
        }
        if (users.usersByIds(setOf(binding.actorId)).none { it.active && it.technician }) fail(WarehouseErrorCode.WRONG_CUSTODIAN)
        return ValidatedWorkOrderContext(binding.authorizationId, binding.workOrderId, binding.customerId,
            binding.actorId, binding.purpose, binding.revisions, binding.authorityEpoch, binding.cutoverEpoch)
    }
    private fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code, code.name))
}
