package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.workorder.*
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Connection
import java.util.UUID

@Component
class WorkOrderMaterialContextAdapter(private val entityManager: EntityManager, private val authority: CurrentAuthorityApi,
    private val cutovers: InventoryTenantCutoverApi, private val users: IamApi, private val customers: CustomerApi) : WorkOrderMaterialContextApi {
    @Transactional(timeout = 30)
    override fun read(workOrderId: UUID): WorkOrderMaterialContext {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        val current = authority.lockCurrent()
        return snapshot(workOrderId, current).also { authorize(it, current, false) }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lock(workOrderId: UUID, expectedRevision: Long, authority: AuthorityFence): WorkOrderMaterialContext {
        authority.assertHeld()
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK).assertHeld()
        val current = this.authority.lockCurrent()
        if (authority.identity != current.fence.identity || authority.epoch != current.fence.epoch) fail(WarehouseErrorCode.STALE_AUTHORITY)
        val context = snapshot(workOrderId, current)
        authorize(context, current, true)
        if (context.workOrderRevision != expectedRevision) fail(WarehouseErrorCode.STALE_REVISION)
        if (!context.active || context.cancelled) fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        context.customerId?.let { if (customers.findCustomer(it) == null) fail(WarehouseErrorCode.NOT_FOUND) }
        return context
    }

    private fun snapshot(id: UUID, current: CurrentAuthority): WorkOrderMaterialContext =
        entityManager.unwrap(Session::class.java).doReturningWork { connection: Connection ->
            check(!connection.autoCommit)
            current.fence.assertHeld()
            connection.prepareStatement("SELECT * FROM work_order WHERE tenant_id=? AND id=? FOR UPDATE").use { query ->
                query.setObject(1, TenantContext.tenantId()); query.setObject(2, id)
                query.executeQuery().use { row ->
                    if (!row.next()) fail(WarehouseErrorCode.NOT_FOUND)
                    val area = row.getObject("area_id", UUID::class.java)
                    val scope = current.areaScope
                    if (scope is AuthorityScope.Restricted && area !in scope.ids) fail(WarehouseErrorCode.NOT_FOUND)
                    val roster = connection.prepareStatement("SELECT technician_id FROM work_order_assignee WHERE tenant_id=? AND work_order_id=?").use { statement ->
                        statement.setObject(1, TenantContext.tenantId()); statement.setObject(2, id)
                        statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
                    }
                    val activeRoster = users.usersByIds(roster).filter { it.active && it.technician }.map { it.id }.toSet()
                    val customer = row.getObject("customer_id", UUID::class.java)
                    val type = row.getString("type")
                    val action = when (type) {
                        "PSB" -> WorkOrderMaterialAction.INSTALL
                        "REPAIR" -> WorkOrderMaterialAction.REPAIR
                        "MIGRATION" -> WorkOrderMaterialAction.REPLACE
                        "DISMANTLE" -> WorkOrderMaterialAction.REMOVE
                        "PREVENTIVE" -> WorkOrderMaterialAction.PREVENTIVE
                        else -> fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    }
                    WorkOrderMaterialContext(id, row.getString("code"), customer, row.getObject("subscription_id", UUID::class.java),
                        row.getObject("order_id", UUID::class.java), null, area, activeRoster,
                        row.getString("status") !in setOf("DONE", "CANCELLED"), row.getString("status") == "CANCELLED", action,
                        type, row.getLong("warehouse_revision"), row.getTimestamp("scheduled_at")?.toInstant(), null)
                }
            }
        }

    private fun authorize(context: WorkOrderMaterialContext, current: CurrentAuthority, mutation: Boolean) {
        if (current.platformAdmin) return
        val dispatcher = if (mutation) setOf("workorder.order.update", "workorder.order.assign") else setOf("workorder.order.view")
        if (current.permissions.any { it in dispatcher }) return
        if ("workorder.order.field" in current.permissions && current.fence.identity.userId in context.activeAssigneeIds) return
        fail(WarehouseErrorCode.FORBIDDEN)
    }

    private fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code, code.name))
}
