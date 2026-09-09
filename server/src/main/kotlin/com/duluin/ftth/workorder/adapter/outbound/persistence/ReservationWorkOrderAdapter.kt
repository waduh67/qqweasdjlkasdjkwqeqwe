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
class ReservationWorkOrderAdapter(private val entityManager: EntityManager) : InventoryReservationWorkOrderPort {
    override fun lock(id: UUID, current: CurrentAuthority?, expectedRevision: Long?): ReservationWorkOrder {
        current?.fence?.assertHeld()
        return entityManager.unwrap(Session::class.java).doReturningWork { connection: Connection ->
            check(!connection.autoCommit)
            connection.prepareStatement("SELECT warehouse_revision,area_id,status,scheduled_at FROM work_order WHERE tenant_id=? AND id=? FOR UPDATE").use { query ->
                query.setObject(1, TenantContext.tenantId()); query.setObject(2, id)
                query.executeQuery().use { row ->
                    if (!row.next()) fail(WarehouseErrorCode.NOT_FOUND)
                    val area = row.getObject("area_id", UUID::class.java)
                    val scope = current?.areaScope
                    if (scope is AuthorityScope.Restricted && area !in scope.ids) fail(WarehouseErrorCode.FORBIDDEN)
                    val revision = row.getLong("warehouse_revision")
                    if (expectedRevision != null && expectedRevision != revision) fail(WarehouseErrorCode.STALE_REVISION)
                    ReservationWorkOrder(revision, row.getString("status") !in setOf("CANCELLED", "DONE"), row.getTimestamp("scheduled_at")?.toInstant(), null)
                }
            }
        }
    }
    private fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code, code.name))
}
