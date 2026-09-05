package com.duluin.ftth.mobile.workorders

import com.duluin.ftth.mobile.domain.InMemoryOutbox
import com.duluin.ftth.mobile.domain.ObserveWorkOrders
import com.duluin.ftth.mobile.domain.PermissionState
import com.duluin.ftth.mobile.domain.WorkOrder
import com.duluin.ftth.mobile.domain.WorkOrderPort
import com.duluin.ftth.mobile.domain.WorkOrderState
import com.duluin.ftth.mobile.ui.ScreenContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkOrderReducerTest {
    private val reducer = WorkOrderReducer()

    @Test
    fun permissionDeniedEmitsOneShotPermissionEffect() {
        val transition = reducer.reduce(
            WorkOrderUiState(workOrder = WorkOrderState.Ready),
            WorkOrderIntent.CheckIn(PermissionState.Denied),
        )

        assertEquals(WorkOrderState.PermissionRequired, transition.state.workOrder)
        assertEquals(listOf(WorkOrderEffect.RequestLocationPermission), transition.effects)
    }

    @Test
    fun completionIsAStateTransitionWithAnExplicitEffect() {
        val transition = reducer.reduce(
            WorkOrderUiState(workOrder = WorkOrderState.InProgress),
            WorkOrderIntent.CheckOut,
        )

        assertEquals(WorkOrderState.Completed, transition.state.workOrder)
        assertEquals(listOf(WorkOrderEffect.WorkOrderCompleted), transition.effects)
    }

    @Test
    fun screenContentMapsEverySupportedWorkOrderCondition() {
        assertEquals(ScreenContent.Loading, WorkOrderUiState(WorkOrderState.Loading).screenContent())
        assertEquals(ScreenContent.Content, WorkOrderUiState(WorkOrderState.Ready).screenContent())
        assertEquals(ScreenContent.Error("gagal"), WorkOrderUiState(WorkOrderState.Error("gagal")).screenContent())
        assertEquals(ScreenContent.Offline("Perangkat sedang offline. Perubahan akan diantrikan."), WorkOrderUiState(WorkOrderState.Offline).screenContent())
        assertEquals(ScreenContent.PermissionDenied("Izin lokasi diperlukan untuk check-in."), WorkOrderUiState(WorkOrderState.PermissionRequired).screenContent())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun storeRestoresSavedUiStateAndPersistsLoadedState() = runTest {
        val saver = WorkOrderStateSaver(WorkOrderUiState(workOrder = WorkOrderState.Ready))
        val feature = WorkOrderFeature(
            ObserveWorkOrders(object : WorkOrderPort {
                override suspend fun list(): Result<List<WorkOrder>> = Result.success(emptyList())
                override suspend fun detail(id: String): Result<WorkOrder> = Result.failure(IllegalStateException("unused"))
            }),
            InMemoryOutbox(),
        )
        val store = WorkOrderViewModel(feature, saver, StandardTestDispatcher(testScheduler))

        assertEquals(WorkOrderState.Ready, store.state.value.workOrder)
        store.dispatch(WorkOrderIntent.Load)
        runCurrent()
        assertEquals(WorkOrderState.Ready, saver.saved?.workOrder)
    }
}
