package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.security.AuthorityScope
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
class RmaWorkOrderAdapter(private val entityManager: EntityManager, private val authority: CurrentAuthorityApi,
    private val users: IamApi) : InventoryRmaWorkOrderPort {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun lock(workOrderId: UUID, revision: Long, customerId: UUID, technicianId: UUID, authority: AuthorityFence, requireCurrent: Boolean) {
        authority.assertHeld()
        val current = this.authority.lockCurrent()
        if (current.fence.identity != authority.identity || current.fence.epoch != authority.epoch) fail(WarehouseErrorCode.STALE_AUTHORITY)
        entityManager.unwrap(Session::class.java).doWork { connection ->
            connection.prepareStatement("SELECT customer_id,area_id,type,status,warehouse_revision FROM work_order WHERE tenant_id=? AND id=? FOR UPDATE").use { query ->
                query.setObject(1, authority.identity.tenantId); query.setObject(2, workOrderId)
                query.executeQuery().use { row ->
                    if (!row.next()) fail(WarehouseErrorCode.NOT_FOUND)
                    if (row.getObject("customer_id", UUID::class.java) != customerId || row.getString("type") != "REPAIR" ||
                        requireCurrent && row.getString("status") !in setOf("ASSIGNED", "IN_PROGRESS")) fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    if (requireCurrent && row.getLong("warehouse_revision") != revision) fail(WarehouseErrorCode.STALE_REVISION)
                    val scope = current.areaScope
                    if (scope is AuthorityScope.Restricted && row.getObject("area_id", UUID::class.java) !in scope.ids) fail(WarehouseErrorCode.FORBIDDEN)
                }
            }
            connection.prepareStatement("SELECT technician_id FROM work_order_assignee WHERE tenant_id=? AND work_order_id=? AND technician_id=?").use { query ->
                query.setObject(1, authority.identity.tenantId); query.setObject(2, workOrderId); query.setObject(3, technicianId)
                query.executeQuery().use { if (requireCurrent && !it.next()) fail(WarehouseErrorCode.WRONG_CUSTODIAN) }
            }
        }
        if (requireCurrent && users.usersByIds(setOf(technicianId)).none { it.active && it.technician }) fail(WarehouseErrorCode.WRONG_CUSTODIAN)
    }
    private fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code, code.name))
}
