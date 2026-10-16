package com.karuhun.ftth.mobile.workorders

import com.karuhun.ftth.mobile.domain.ObserveWorkOrders
import com.karuhun.ftth.mobile.domain.Outbox
import com.karuhun.ftth.mobile.domain.OutboxStatus
import com.karuhun.ftth.mobile.domain.PermissionState
import com.karuhun.ftth.mobile.domain.WorkOrderEvent
import com.karuhun.ftth.mobile.domain.WorkOrderState
import com.karuhun.ftth.mobile.domain.reduce

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
