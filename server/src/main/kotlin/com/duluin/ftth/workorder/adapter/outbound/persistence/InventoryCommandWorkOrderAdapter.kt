package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.*
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import java.sql.Connection
import java.util.UUID

@Component
class InventoryCommandWorkOrderAdapter(private val entityManager: EntityManager) : InventoryCommandWorkOrderPort {
    override fun lock(id: UUID, expectedRevision: Long?, authority: CurrentAuthority, customerId: UUID?, fieldAction: Boolean): Long {
        authority.fence.assertHeld()
        return entityManager.unwrap(Session::class.java).doReturningWork { connection: Connection ->
            try {
            connection.prepareStatement("SELECT warehouse_revision,area_id,customer_id,status FROM work_order WHERE tenant_id=? AND id=? FOR UPDATE").use { statement ->
                statement.setObject(1, TenantContext.tenantId()); statement.setObject(2, id)
                statement.executeQuery().use { row ->
                    if (!row.next()) fail(WarehouseErrorCode.NOT_FOUND)
                    val area = row.getObject("area_id", UUID::class.java)
                    val scope = authority.areaScope
                    if (area != null && scope is AuthorityScope.Restricted && area !in scope.ids) fail(WarehouseErrorCode.FORBIDDEN)
                    if (customerId != null && row.getObject("customer_id", UUID::class.java) != customerId) fail(WarehouseErrorCode.FORBIDDEN)
                    if (fieldAction) {
                        if (row.getString("status") !in setOf("ASSIGNED", "IN_PROGRESS")) fail(WarehouseErrorCode.STALE_REVISION)
                        connection.prepareStatement("SELECT 1 FROM work_order_assignee WHERE tenant_id=? AND work_order_id=? AND technician_id=?").use { roster ->
                            roster.setObject(1, TenantContext.tenantId()); roster.setObject(2, id); roster.setObject(3, authority.fence.identity.userId)
                            roster.executeQuery().use { if (!it.next()) fail(WarehouseErrorCode.FORBIDDEN) }
                        }
                    }
                    val revision = row.getLong("warehouse_revision")
                    if (expectedRevision != null && revision != expectedRevision) fail(WarehouseErrorCode.STALE_REVISION)
                    revision
                }
            }
            } catch (failure: java.sql.SQLException) {
                if (failure.sqlState in setOf("40001", "40P01", "55P03")) fail(WarehouseErrorCode.STALE_REVISION)
                throw failure
            }
        }
    }
    private fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code, code.name))
}
