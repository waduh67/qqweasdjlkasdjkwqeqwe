package com.duluin.ftth.mobile.mvi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MviViewModelTest {
    @Test
    fun viewModelOwnsStoreAndStopsDispatchAfterClear() = runTest {
        val viewModel = UniqueCounterViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(UniqueCounterIntent.Increment)
        runCurrent()
        assertEquals(1, viewModel.state.value.count)

        viewModel.clearForTest()
        viewModel.dispatch(UniqueCounterIntent.Increment)
        runCurrent()
        assertEquals(1, viewModel.state.value.count)
    }
}

private data class UniqueCounterState(val count: Int = 0) : MviState
private sealed interface UniqueCounterIntent : MviIntent { data object Increment : UniqueCounterIntent }
private class UniqueCounterReducer : MviReducer<UniqueCounterState, UniqueCounterIntent, MviAction, MviEffect> {
    override fun reduce(state: UniqueCounterState, intent: UniqueCounterIntent) = MviTransition<UniqueCounterState, MviAction, MviEffect>(
        when (intent) { UniqueCounterIntent.Increment -> state.copy(count = state.count + 1) },
    )
}
private class UniqueCounterViewModel(dispatcher: CoroutineDispatcher) : MviViewModel<UniqueCounterState, UniqueCounterIntent, MviAction, MviEffect>(
    initialState = UniqueCounterState(),
    reducer = UniqueCounterReducer(),
    dispatcher = dispatcher,
) {
    fun clearForTest() = onCleared()
}
