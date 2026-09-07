package com.duluin.ftth.workorder

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.inventory.MaterialMode
import com.duluin.ftth.inventory.MaterialRevisions
import java.time.Instant
import java.util.UUID

interface WorkOrderMaterialContextApi {
    fun read(workOrderId: UUID): WorkOrderMaterialContext
    fun lock(workOrderId: UUID, expectedRevision: Long, authority: AuthorityFence): WorkOrderMaterialContext
}

data class WorkOrderMaterialContext(
    val workOrderId: UUID,
    val code: String,
    val customerId: UUID?,
    val subscriptionId: UUID?,
    val orderId: UUID?,
    val visitId: UUID?,
    val areaId: UUID?,
    val activeAssigneeIds: Set<UUID>,
    val active: Boolean,
    val cancelled: Boolean,
    val action: WorkOrderMaterialAction,
    val materialMode: MaterialMode,
    val revisions: MaterialRevisions,
    val scheduledAt: Instant?,
    val scheduledEndAt: Instant?,
)

enum class WorkOrderMaterialAction { INSTALL, REPAIR, REPLACE, REMOVE, NETWORK, PREVENTIVE, RETURN_CUSTOMER_RMA }
