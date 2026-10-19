package com.karuhun.ftth.workorder.application.service

import com.karuhun.ftth.common.infrastructure.security.AccessChecker
import com.karuhun.ftth.common.security.AuthorityScope
import com.karuhun.ftth.common.tenant.TenantContext
import com.karuhun.ftth.customer.CustomerApi
import com.karuhun.ftth.iam.CurrentAuthorityApi
import com.karuhun.ftth.iam.CurrentAuthority
import com.karuhun.ftth.inventory.*
import com.karuhun.ftth.common.warehouse.warehouseFailure
import com.karuhun.ftth.common.infrastructure.time.referenceTimestamp
import com.karuhun.ftth.workorder.adapter.outbound.persistence.ReferenceWorkIntakeStore
import com.karuhun.ftth.workorder.application.port.inbound.*
import com.karuhun.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.karuhun.ftth.workorder.domain.model.WorkOrder
import com.karuhun.ftth.workorder.domain.model.WorkOrderPriority
import com.karuhun.ftth.workorder.domain.model.WorkOrderType
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
@Transactional
class ReferenceWorkIntakeService(private val store: ReferenceWorkIntakeStore, private val orders: WorkOrderRepository,
    private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi, private val customers: CustomerApi,
    private val accessChecker: AccessChecker, private val entityManager: EntityManager) {

    fun open(source: ReferenceWorkSource, sourceId: UUID, type: WorkOrderType, title: String, description: String?,
        priority: WorkOrderPriority, customerId: UUID, areaId: UUID?, scheduledAt: Instant?, subscriptionId: UUID? = null): WorkOrder {
        val fence = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.REFERENCE_WORK_ORDER)
        val customer = customers.findCustomer(customerId) ?: warehouseFailure(WarehouseErrorCode.NOT_FOUND)
        val workArea = areaId ?: customer.areaId
        val current = if (source == ReferenceWorkSource.PREVENTIVE) null else authority.lockCurrent().also { actor ->
            accessChecker.assertWritable()
            if (!actor.platformAdmin && "workorder.order.create" !in actor.permissions) warehouseFailure(WarehouseErrorCode.FORBIDDEN)
            val scope = actor.areaScope
            if (!actor.platformAdmin && scope is AuthorityScope.Restricted && (workArea == null || workArea !in scope.ids)) warehouseFailure(WarehouseErrorCode.NOT_FOUND)
        }
        if (title.isBlank() || title.length > 200 || (description?.length ?: 0) > 2000 ||
            (title + description.orEmpty()).any { it == '<' || it == '>' || it.isISOControl() && it.code != 10 }) warehouseFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val now = referenceTimestamp()
        val order = WorkOrder.open(TenantContext.tenantId(), type, title, description, priority, customerId, null, workArea,
            scheduledAt?.let(::referenceTimestamp), emptySet(), current?.fence?.identity?.userId, subscriptionId, null, now)
        val saved = orders.save(order)
        entityManager.flush()
        store.save(ReferenceWorkIntakeView(order.id, order.code, source, sourceId, type, order.title, order.description.orEmpty(),
            priority, customerId, workArea, order.scheduledAt, now, false), current?.fence?.identity?.userId, current?.fence?.epoch ?: 0L, fence.snapshot.epoch)
        return saved
    }
    fun list(page: Int, size: Int, search: String?): WarehousePage<ReferenceWorkIntakeView> {
        if (page < 0 || size !in 1..100 || search != null && search.length > 200) warehouseFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val current = readAuthority()
        return store.list(page, size, current.areaScope, search)
    }
    fun detail(id: UUID): ReferenceWorkIntakeView {
        val current = readAuthority()
        val intake = store.get(id)
        val scope = current.areaScope
        if (!current.platformAdmin && scope is AuthorityScope.Restricted && (intake.areaId == null || intake.areaId !in scope.ids)) warehouseFailure(WarehouseErrorCode.NOT_FOUND)
        return intake
    }
    private fun readAuthority(): CurrentAuthority {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        return authority.lockCurrent().also {
            if (!it.platformAdmin && "workorder.order.view" !in it.permissions) warehouseFailure(WarehouseErrorCode.FORBIDDEN)
        }
    }
}
