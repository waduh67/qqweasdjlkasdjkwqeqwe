package com.duluin.ftth.mobile.domain

data class WorkOrder(val id: String, val title: String, val address: String)

enum class PermissionState { Unknown, Granted, Denied }
enum class SessionState { SignedOut, Authenticated }
enum class Route { SignIn, PermissionHelp, WorkOrders }

sealed interface WorkOrderState {
    data object Loading : WorkOrderState
    data object Ready : WorkOrderState
    data object InProgress : WorkOrderState
    data object Completed : WorkOrderState
    data object PermissionRequired : WorkOrderState
    data class Error(val message: String) : WorkOrderState
    data object Offline : WorkOrderState
}

sealed interface WorkOrderEvent {
    data class CheckIn(val permission: PermissionState) : WorkOrderEvent
    data object CheckOut : WorkOrderEvent
}

fun reduce(state: WorkOrderState, event: WorkOrderEvent): WorkOrderState = when (event) {
    is WorkOrderEvent.CheckIn -> if (state == WorkOrderState.Ready && event.permission == PermissionState.Granted) {
        WorkOrderState.InProgress
    } else if (event.permission != PermissionState.Granted) WorkOrderState.PermissionRequired else state
    WorkOrderEvent.CheckOut -> if (state == WorkOrderState.InProgress) WorkOrderState.Completed else state
}

fun routeFor(session: SessionState, permission: PermissionState): Route = when {
    session == SessionState.SignedOut -> Route.SignIn
    permission == PermissionState.Denied -> Route.PermissionHelp
    else -> Route.WorkOrders
}

data class OutboxOperation(val key: String, val payload: String)
enum class EnqueueResult { Accepted, Replayed, Conflict }

interface Outbox {
    fun enqueue(operation: OutboxOperation): EnqueueResult
    fun status(): OutboxStatus
}

data class OutboxStatus(val pending: Int, val conflicts: Int, val encryptedAtRest: Boolean)

class InMemoryOutbox : Outbox {
    private val operations = linkedMapOf<String, OutboxOperation>()
    private var conflicts = 0

    override fun enqueue(operation: OutboxOperation): EnqueueResult {
        val existing = operations[operation.key]
        return when {
            existing == null -> { operations[operation.key] = operation; EnqueueResult.Accepted }
            existing == operation -> EnqueueResult.Replayed
            else -> { conflicts += 1; EnqueueResult.Conflict }
        }
    }

    override fun status() = OutboxStatus(operations.size, conflicts, encryptedAtRest = false)
}
