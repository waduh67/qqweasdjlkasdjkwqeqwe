package com.karuhun.ftth.workorder.application.port.inbound

import com.karuhun.ftth.workorder.domain.model.WorkOrderPriority
import com.karuhun.ftth.workorder.domain.model.WorkOrderType
import java.time.Instant
import java.util.UUID

enum class WorkOrderKpiCategory(val points: Int) { NONE(0), PSB(1), PROJECT(3) }
data class ReferenceWorkOrderTypeInput(val name: String, val workType: WorkOrderType, val materialRequired: Boolean,
    val photoSlots: List<String>, val active: Boolean = true, val expectedRevision: Long = 0,
    val kpiCategory: WorkOrderKpiCategory = if (workType == WorkOrderType.PSB) WorkOrderKpiCategory.PSB else WorkOrderKpiCategory.NONE)
data class ReferenceWorkOrderTypeView(val id: UUID, val revision: Long, val name: String, val workType: WorkOrderType,
    val materialRequired: Boolean, val photoSlots: List<String>, val active: Boolean, val deleted: Boolean = false,
    val kpiCategory: WorkOrderKpiCategory = if (workType == WorkOrderType.PSB) WorkOrderKpiCategory.PSB else WorkOrderKpiCategory.NONE)
data class ReferenceWorkOrderInput(val typeId: UUID, val title: String, val technicianId: UUID, val areaId: UUID,
    val description: String = "", val priority: WorkOrderPriority = WorkOrderPriority.NORMAL, val customerId: UUID? = null,
    val scheduledAt: Instant? = null, val subscriptionId: UUID? = null, val orderId: UUID? = null)
data class ReferenceWorkOrderUpdate(val expectedRevision: Long, val title: String, val description: String,
    val priority: WorkOrderPriority, val customerId: UUID?, val areaId: UUID, val scheduledAt: Instant?)
data class ReferenceWorkOrderAssignment(val expectedRevision: Long, val technicianId: UUID)
enum class ReferenceWorkOrderState { PENDING, BLOCKED, COMPLETED, CANCELLED }
data class ReferenceWorkOrderProgress(val expectedRevision: Long, val state: ReferenceWorkOrderState, val notes: String)
data class ReferenceWorkOrderView(val id: UUID, val code: String, val revision: Long, val type: ReferenceWorkOrderTypeView,
    val title: String, val description: String, val priority: WorkOrderPriority, val customerId: UUID?,
    val technicianId: UUID, val technicianName: String, val areaId: UUID, val scheduledAt: Instant?,
    val state: ReferenceWorkOrderState, val assignmentGeneration: Long, val lastActivityAt: Instant,
    val blockedReason: String?, val createdAt: Instant)
data class ReferenceWorkOrderEvent(val id: UUID, val revision: Long, val action: String, val actorName: String,
    val notes: String, val recordedAt: Instant)
data class ReferenceWorkOrderDetail(val workOrder: ReferenceWorkOrderView, val overdue: Boolean, val overdueAt: Instant,
    val timeline: List<ReferenceWorkOrderEvent>, val completion: ReferenceWorkOrderCompletionView? = null, val customerLocked: Boolean = false)
data class ReferenceWorkOrderMaterialInput(val stockIdentityId: UUID, val quantityBase: String)
data class ReferenceWorkOrderCompletionInput(val expectedRevision: Long, val notes: String = "",
    val materials: List<ReferenceWorkOrderMaterialInput> = emptyList())
typealias ReferenceCompletionPhoto = com.karuhun.ftth.workorder.ReferenceCompletionPhoto
typealias ReferenceCompletionMaterial = com.karuhun.ftth.workorder.ReferenceCompletionMaterial
typealias ReferenceWorkOrderCompletionView = com.karuhun.ftth.workorder.ReferenceWorkOrderCompletionView
