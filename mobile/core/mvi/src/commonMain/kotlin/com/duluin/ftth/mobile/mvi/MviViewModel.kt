package com.duluin.ftth.mobile.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

abstract class MviViewModel<State : MviState, Intent : MviIntent, Action : MviAction, Effect : MviEffect>(
    initialState: State,
    reducer: MviReducer<State, Intent, Action, Effect>,
    actionHandler: (suspend (Action) -> Intent?)? = null,
    stateActionHandler: (suspend (State, Action) -> Intent?)? = null,
    stateSaver: MviStateSaver<State>? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val store = MviStore(
        initialState = initialState,
        reducer = reducer,
        parentScope = viewModelScope,
        actionHandler = actionHandler,
        stateActionHandler = stateActionHandler,
        stateSaver = stateSaver,
        dispatcher = dispatcher,
    )

    val state: StateFlow<State> = store.state
    val effects: SharedFlow<Effect> = store.effects

    suspend fun dispatch(intent: Intent) = store.dispatch(intent)

    fun accept(intent: Intent) {
        viewModelScope.launch { store.dispatch(intent) }
    }

    override fun onCleared() {
        store.close()
        super.onCleared()
    }
}
