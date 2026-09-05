package com.duluin.ftth.mobile.attendance

import com.duluin.ftth.mobile.domain.AttendanceCommand
import com.duluin.ftth.mobile.domain.AttendanceOperation
import com.duluin.ftth.mobile.domain.AttendancePort
import com.duluin.ftth.mobile.domain.AttendanceSnapshot
import com.duluin.ftth.mobile.domain.AttendanceSubmission
import com.duluin.ftth.mobile.domain.Permission
import com.duluin.ftth.mobile.domain.SecureOutboxPort
import com.duluin.ftth.mobile.domain.OutboxOperation
import com.duluin.ftth.mobile.domain.OutboxIdentity
import com.duluin.ftth.mobile.domain.SecureOutboxOperation
import com.duluin.ftth.mobile.mvi.MviAction
import com.duluin.ftth.mobile.mvi.MviEffect
import com.duluin.ftth.mobile.mvi.MviIntent
import com.duluin.ftth.mobile.mvi.MviReducer
import com.duluin.ftth.mobile.mvi.MviState
import com.duluin.ftth.mobile.mvi.MviViewModel
import com.duluin.ftth.mobile.mvi.MviTransition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

sealed interface AttendanceStatus {
    data object Loading : AttendanceStatus
    data object Ready : AttendanceStatus
    data object Submitting : AttendanceStatus
    data object Offline : AttendanceStatus
    data object PermissionDenied : AttendanceStatus
    data class Conflict(val message: String) : AttendanceStatus
    data class Error(val message: String) : AttendanceStatus
}

data class AttendanceUiState(
    val snapshot: AttendanceSnapshot? = null,
    val status: AttendanceStatus = AttendanceStatus.Loading,
    val permissions: Set<Permission> = emptySet(),
) : MviState

sealed interface AttendanceIntent : MviIntent {
    data object Load : AttendanceIntent
    data class Loaded(val snapshot: AttendanceSnapshot) : AttendanceIntent
    data object CheckIn : AttendanceIntent
    data object CheckOut : AttendanceIntent
    data class Submitted(val snapshot: AttendanceSnapshot) : AttendanceIntent
    data class SubmissionConflicted(val message: String) : AttendanceIntent
    data object Offline : AttendanceIntent
    data object Denied : AttendanceIntent
    data class Failed(val message: String) : AttendanceIntent
}

sealed interface AttendanceAction : MviAction {
    data object Load : AttendanceAction
    data class Submit(val operation: AttendanceOperation) : AttendanceAction
}

sealed interface AttendanceEffect : MviEffect {
    data object AttendanceQueued : AttendanceEffect
}

class AttendanceReducer : MviReducer<AttendanceUiState, AttendanceIntent, AttendanceAction, AttendanceEffect> {
    override fun reduce(state: AttendanceUiState, intent: AttendanceIntent): MviTransition<AttendanceUiState, AttendanceAction, AttendanceEffect> = when (intent) {
        AttendanceIntent.Load -> MviTransition(state.copy(status = AttendanceStatus.Loading), actions = listOf(AttendanceAction.Load))
        is AttendanceIntent.Loaded -> MviTransition(state.copy(snapshot = intent.snapshot, status = AttendanceStatus.Ready))
        AttendanceIntent.CheckIn -> submit(state, AttendanceOperation.CheckIn)
        AttendanceIntent.CheckOut -> submit(state, AttendanceOperation.CheckOut)
        is AttendanceIntent.Submitted -> MviTransition(state.copy(snapshot = intent.snapshot, status = AttendanceStatus.Ready))
        is AttendanceIntent.SubmissionConflicted -> MviTransition(state.copy(status = AttendanceStatus.Conflict(intent.message)))
        AttendanceIntent.Offline -> MviTransition(state.copy(status = AttendanceStatus.Offline), effects = listOf(AttendanceEffect.AttendanceQueued))
        AttendanceIntent.Denied -> MviTransition(state.copy(status = AttendanceStatus.PermissionDenied))
        is AttendanceIntent.Failed -> MviTransition(state.copy(status = AttendanceStatus.Error(intent.message)))
    }

    private fun submit(state: AttendanceUiState, operation: AttendanceOperation): MviTransition<AttendanceUiState, AttendanceAction, AttendanceEffect> =
        if (Permission.AttendanceSelf in state.permissions && state.snapshot?.periodLocked == false) {
            MviTransition(state.copy(status = AttendanceStatus.Submitting), actions = listOf(AttendanceAction.Submit(operation)))
        } else {
            MviTransition(state.copy(status = AttendanceStatus.PermissionDenied))
        }
}

class AttendanceFeature(
    private val attendance: AttendancePort,
    private val outbox: SecureOutboxPort,
    private val operationKey: () -> String,
    private val identity: OutboxIdentity,
) {
    suspend fun load(): AttendanceIntent = attendance.snapshot().fold(
        onSuccess = AttendanceIntent::Loaded,
        onFailure = { AttendanceIntent.Failed(it.message ?: "Kehadiran tidak dapat dimuat") },
    )

    suspend fun submit(operation: AttendanceOperation, state: AttendanceUiState): AttendanceIntent {
        val snapshot = state.snapshot ?: return AttendanceIntent.Failed("Data kehadiran belum siap")
        val command = AttendanceCommand(operation, operationKey(), snapshot.revision)
        return when (val result = attendance.submit(command)) {
            is AttendanceSubmission.Accepted -> AttendanceIntent.Submitted(result.snapshot)
            is AttendanceSubmission.Conflict -> AttendanceIntent.SubmissionConflicted(result.message)
            AttendanceSubmission.Denied -> AttendanceIntent.Denied
            AttendanceSubmission.Offline -> {
                outbox.enqueueSecure(
                    SecureOutboxOperation(
                        identity.userId,
                        identity.deviceId,
                        identity.sessionId,
                        "attendance.${command.operation}",
                        command.operationKey,
                        "${command.operation}:${command.revision}",
                        command.revision,
                        byteArrayOf(),
                    ),
                )
                AttendanceIntent.Offline
            }
        }
    }
}

class AttendanceViewModel(
    feature: AttendanceFeature,
    permissions: Set<Permission>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MviViewModel<AttendanceUiState, AttendanceIntent, AttendanceAction, AttendanceEffect>(
            initialState = AttendanceUiState(permissions = permissions),
            reducer = AttendanceReducer(),
            stateActionHandler = { currentState, action -> when (action) {
                AttendanceAction.Load -> feature.load()
                is AttendanceAction.Submit -> feature.submit(action.operation, currentState)
            } },
            dispatcher = dispatcher,
)
