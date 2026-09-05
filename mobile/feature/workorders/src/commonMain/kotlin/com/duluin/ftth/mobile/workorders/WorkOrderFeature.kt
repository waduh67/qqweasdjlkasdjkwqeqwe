package com.duluin.ftth.mobile.workorders

import com.duluin.ftth.mobile.domain.ObserveWorkOrders
import com.duluin.ftth.mobile.domain.Outbox
import com.duluin.ftth.mobile.domain.OutboxStatus
import com.duluin.ftth.mobile.domain.PermissionState
import com.duluin.ftth.mobile.domain.WorkOrderEvent
import com.duluin.ftth.mobile.domain.WorkOrderState
import com.duluin.ftth.mobile.domain.reduce

data class WorkOrderScreenModel(
    val state: WorkOrderState,
    val outbox: OutboxStatus,
    val permission: PermissionState,
)

class WorkOrderFeature(private val observe: ObserveWorkOrders, private val outbox: Outbox) {
    suspend fun load() = WorkOrderScreenModel(observe(), outbox.status(), PermissionState.Unknown)
    fun checkIn(permission: PermissionState, state: WorkOrderState) = reduce(state, WorkOrderEvent.CheckIn(permission))
    fun checkOut(state: WorkOrderState) = reduce(state, WorkOrderEvent.CheckOut)
}
