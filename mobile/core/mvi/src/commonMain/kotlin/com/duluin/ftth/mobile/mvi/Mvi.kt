package com.duluin.ftth.mobile.mvi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface MviIntent
interface MviAction
interface MviState
interface MviEffect

data class MviTransition<State : MviState, Action : MviAction, Effect : MviEffect>(
    val state: State,
    val actions: List<Action> = emptyList(),
    val effects: List<Effect> = emptyList(),
)

interface MviReducer<State : MviState, Intent : MviIntent, Action : MviAction, Effect : MviEffect> {
    fun reduce(state: State, intent: Intent): MviTransition<State, Action, Effect>
}

interface MviStateSaver<State : MviState> {
    var saved: State?
}

class MviStore<State : MviState, Intent : MviIntent, Action : MviAction, Effect : MviEffect>(
    initialState: State,
    private val reducer: MviReducer<State, Intent, Action, Effect>,
    parentScope: CoroutineScope,
    private val actionHandler: (suspend (Action) -> Intent?)? = null,
    private val stateSaver: MviStateSaver<State>? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val storeJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + storeJob + dispatcher)
    private val mutex = Mutex()
    private val actions = Channel<Action>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(stateSaver?.saved ?: initialState)
    private val mutableEffects = MutableSharedFlow<Effect>(replay = 0, extraBufferCapacity = 16)

    val state: StateFlow<State> = mutableState
    val effects: SharedFlow<Effect> = mutableEffects
    val isClosed: Boolean get() = !storeJob.isActive

    init {
        scope.launch {
            for (action in actions) {
                val intent = actionHandler?.invoke(action) ?: continue
                dispatch(intent)
            }
        }
    }

    suspend fun dispatch(intent: Intent) {
        val transition = mutex.withLock {
            if (isClosed) null
            else reducer.reduce(mutableState.value, intent).also {
                mutableState.value = it.state
                stateSaver?.saved = it.state
            }
        } ?: return
        transition.effects.forEach { mutableEffects.emit(it) }
        transition.actions.forEach { actions.send(it) }
    }

    override fun close() {
        actions.close()
        storeJob.cancel()
    }

    companion object {
        fun <State : MviState> restore(saver: MviStateSaver<State>, fallback: State): State = saver.saved ?: fallback
    }
}
