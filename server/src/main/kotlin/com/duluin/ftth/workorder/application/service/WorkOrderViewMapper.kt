package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderAssigneeView
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderView
import com.duluin.ftth.workorder.domain.model.WorkOrder
import org.springframework.stereotype.Component
import java.util.UUID

data class WorkOrderViewContext(val customer: CustomerRef?, val assigneeNames: Map<UUID, String?>, val approverName: String?)

@Component
class WorkOrderViewMapper(private val customers: CustomerApi, private val users: IamApi) {
    fun single(workOrder: WorkOrder): WorkOrderView = view(workOrder, WorkOrderViewContext(
        workOrder.customerId?.let { customers.findCustomer(it) },
        users.usersByIds(workOrder.assignees).associate { it.id to it.name },
        workOrder.approvedBy?.let { users.findUser(it)?.name }))

    fun view(workOrder: WorkOrder, context: WorkOrderViewContext): WorkOrderView = with(workOrder) {
        WorkOrderView(
            id = id, code = code, type = type.name, status = status.name, priority = priority.name,
            title = title, description = description, customerId = customerId, customerName = context.customer?.name,
            subscriptionId = subscriptionId, incidentId = incidentId, areaId = areaId,
            destinationLat = context.customer?.location?.latitude, destinationLng = context.customer?.location?.longitude,
            assignees = assignees.map { WorkOrderAssigneeView(it, context.assigneeNames[it]) },
            scheduledAt = scheduledAt, assignedAt = assignedAt, startedAt = startedAt, completedAt = completedAt,
            resolutionNote = resolutionNote, cancelReason = cancelReason, rxBeforeDbm = rxBeforeDbm, rxAfterDbm = rxAfterDbm,
            approvalStatus = approvalStatus?.name, approvedBy = approvedBy, approvedByName = context.approverName,
            approvedAt = approvedAt, approvalNote = approvalNote, createdAt = createdAt,
        )
    }
}
