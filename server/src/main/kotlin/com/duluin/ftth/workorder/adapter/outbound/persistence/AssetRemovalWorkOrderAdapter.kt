package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityFence
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
@Transactional(propagation = Propagation.MANDATORY)
class AssetRemovalWorkOrderAdapter(private val authorities: CurrentAuthorityApi, private val users: IamApi,
    private val entityManager: EntityManager) : AssetRemovalWorkOrderPort {
    override fun lock(request: AssetRemovalWorkOrderRequest, authority: AuthorityFence): Long {
        authority.assertHeld()
        val current = authorities.lockCurrent()
        if (current.fence.identity != authority.identity || current.fence.epoch != authority.epoch) fail(WarehouseErrorCode.STALE_AUTHORITY)
        if (!current.platformAdmin && !current.permissions.containsAll(setOf("workorder.order.field", "customer.onu.assign")))
            fail(WarehouseErrorCode.FORBIDDEN)
        if (users.usersByIds(setOf(authority.identity.userId)).none { it.active && it.technician }) fail(WarehouseErrorCode.WRONG_CUSTODIAN)
        val type = when (request.purpose) {
            DeploymentPurpose.REPLACE -> "MIGRATION"
            DeploymentPurpose.REMOVE -> "DISMANTLE"
            DeploymentPurpose.INSTALL, DeploymentPurpose.RETURN_CUSTOMER_RMA -> fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        }
        return entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            connection.prepareStatement("""SELECT warehouse_revision,area_id FROM work_order WHERE tenant_id=? AND id=?
                AND customer_id=? AND type=? AND status IN ('ASSIGNED','IN_PROGRESS') AND EXISTS(SELECT FROM work_order_assignee
                WHERE tenant_id=work_order.tenant_id AND work_order_id=work_order.id AND technician_id=?) FOR UPDATE""").use { query ->
                query.setObject(1, TenantContext.tenantId()); query.setObject(2, request.workOrderId)
                query.setObject(3, request.customerId); query.setString(4, type); query.setObject(5, authority.identity.userId)
                query.executeQuery().use { row ->
                    if (!row.next()) fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    val scope = current.areaScope
                    if (scope is AuthorityScope.Restricted && row.getObject("area_id", UUID::class.java) !in scope.ids) fail(WarehouseErrorCode.FORBIDDEN)
                    row.getLong("warehouse_revision")
                }
            }
        }
    }
    private fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code, code.name))
}
