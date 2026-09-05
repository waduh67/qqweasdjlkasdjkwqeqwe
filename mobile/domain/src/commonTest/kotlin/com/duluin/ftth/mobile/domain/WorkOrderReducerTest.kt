package com.duluin.ftth.mobile.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkOrderReducerTest {
    @Test
    fun checkInRequiresLocationPermission() {
        val result = reduce(WorkOrderState.Ready, WorkOrderEvent.CheckIn(PermissionState.Denied))
        assertEquals(WorkOrderState.PermissionRequired, result)
    }

    @Test
    fun checkInAndOutAreDeterministic() {
        val checkedIn = reduce(WorkOrderState.Ready, WorkOrderEvent.CheckIn(PermissionState.Granted))
        assertEquals(WorkOrderState.InProgress, checkedIn)
        assertEquals(WorkOrderState.Completed, reduce(checkedIn, WorkOrderEvent.CheckOut))
    }
}
