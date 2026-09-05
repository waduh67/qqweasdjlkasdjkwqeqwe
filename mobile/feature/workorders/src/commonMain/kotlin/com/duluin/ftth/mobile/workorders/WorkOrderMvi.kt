package com.duluin.ftth.mobile.workorders

import com.duluin.ftth.mobile.domain.OutboxStatus
import com.duluin.ftth.mobile.domain.PermissionState
import com.duluin.ftth.mobile.domain.WorkOrderState
import com.duluin.ftth.mobile.mvi.MviAction
import com.duluin.ftth.mobile.mvi.MviEffect
import com.duluin.ftth.mobile.mvi.MviIntent
import com.duluin.ftth.mobile.mvi.MviReducer
import com.duluin.ftth.mobile.mvi.MviState
import com.duluin.ftth.mobile.mvi.MviViewModel
import com.duluin.ftth.mobile.mvi.MviTransition
import com.duluin.ftth.mobile.mvi.MviStateSaver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

data class WorkOrderUiState(
    val workOrder: WorkOrderState = WorkOrderState.Loading,
    val outbox: OutboxStatus = OutboxStatus(0, 0, encryptedAtRest = false),
    val permission: PermissionState = PermissionState.Unknown,
) : MviState

sealed interface WorkOrderIntent : MviIntent {
    data object Load : WorkOrderIntent
    data class Loaded(val model: WorkOrderScreenModel) : WorkOrderIntent
    data class CheckIn(val permission: PermissionState) : WorkOrderIntent
    data object CheckOut : WorkOrderIntent
}

data object LoadWorkOrders : MviAction

sealed interface WorkOrderEffect : MviEffect {
    data object RequestLocationPermission : WorkOrderEffect
    data object WorkOrderCompleted : WorkOrderEffect
}

class WorkOrderStateSaver(override var saved: WorkOrderUiState? = null) : MviStateSaver<WorkOrderUiState>

class WorkOrderViewModel(
    feature: WorkOrderFeature,
    stateSaver: MviStateSaver<WorkOrderUiState>? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MviViewModel<WorkOrderUiState, WorkOrderIntent, LoadWorkOrders, WorkOrderEffect>(
        initialState = WorkOrderUiState(),
        reducer = WorkOrderReducer(),
        stateSaver = stateSaver,
        dispatcher = dispatcher,
        actionHandler = { action -> when (action) {
            LoadWorkOrders -> WorkOrderIntent.Loaded(feature.load())
        } },
)

class WorkOrderReducer : MviReducer<WorkOrderUiState, WorkOrderIntent, LoadWorkOrders, WorkOrderEffect> {
    override fun reduce(
        state: WorkOrderUiState,
        intent: WorkOrderIntent,
    ): MviTransition<WorkOrderUiState, LoadWorkOrders, WorkOrderEffect> = when (intent) {
        WorkOrderIntent.Load -> MviTransition(state, actions = listOf(LoadWorkOrders))
        is WorkOrderIntent.Loaded -> MviTransition(
            state.copy(
                workOrder = intent.model.state,
                outbox = intent.model.outbox,
                permission = intent.model.permission,
            ),
        )
        is WorkOrderIntent.CheckIn -> {
            val next = WorkOrderStateReducer.checkIn(state, intent.permission)
            MviTransition(
                state.copy(workOrder = next, permission = intent.permission),
                effects = if (next == WorkOrderState.PermissionRequired) listOf(WorkOrderEffect.RequestLocationPermission) else emptyList(),
            )
        }
        WorkOrderIntent.CheckOut -> {
            val next = WorkOrderStateReducer.checkOut(state)
            MviTransition(
                state.copy(workOrder = next),
                effects = if (next == WorkOrderState.Completed) listOf(WorkOrderEffect.WorkOrderCompleted) else emptyList(),
            )
        }
    }
}

private object WorkOrderStateReducer {
    fun checkIn(state: WorkOrderUiState, permission: PermissionState) =
        com.duluin.ftth.mobile.domain.reduce(state.workOrder, com.duluin.ftth.mobile.domain.WorkOrderEvent.CheckIn(permission))

    fun checkOut(state: WorkOrderUiState) =
        com.duluin.ftth.mobile.domain.reduce(state.workOrder, com.duluin.ftth.mobile.domain.WorkOrderEvent.CheckOut)
}
