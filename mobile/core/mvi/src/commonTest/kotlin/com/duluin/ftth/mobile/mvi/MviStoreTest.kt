package com.duluin.ftth.mobile.mvi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MviStoreTest {
    @Test
    fun reducerRemainsPureAndIntentsAreReducedInOrder() = runTest {
        val reducer = CounterReducer()
        val first = reducer.reduce(CounterState(), CounterIntent.Add(2))
        val second = reducer.reduce(CounterState(), CounterIntent.Add(2))
        assertEquals(first, second)

        val store = MviStore(CounterState(), reducer, this)
        store.dispatch(CounterIntent.Add(2))
        store.dispatch(CounterIntent.Add(3))
        assertEquals(CounterState(5), store.state.value)
        store.close()
    }

    @Test
    fun effectsAreOneShotAndNeverReplayForANewCollector() = runTest {
        val store = MviStore(CounterState(), CounterReducer(), this)
        val received = mutableListOf<CounterEffect>()
        val collector = launch { received += store.effects.first() }
        runCurrent()

        store.dispatch(CounterIntent.Notify)
        runCurrent()
        collector.cancel()
        assertEquals(listOf<CounterEffect>(CounterEffect.Notified), received)
        assertFalse(store.effects.replayCache.isNotEmpty())
        store.close()
    }

    @Test
    fun closeCancelsOwnedActionWorkDeterministically() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var actionStarted = false
        var actionCancelled = false
        val store = MviStore(
            initialState = CounterState(),
            reducer = CounterReducer(),
            parentScope = this,
            actionHandler = { action ->
                actionStarted = true
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    actionCancelled = true
                }
            },
            dispatcher = dispatcher,
        )

        store.dispatch(CounterIntent.Start)
        runCurrent()
        assertTrue(actionStarted)
        store.close()
        runCurrent()
        assertTrue(store.isClosed)
        assertTrue(actionCancelled)
    }

    @Test
    fun replayConflictAndStateRestorationRemainExplicit() = runTest {
        val saver = RecordingSaver<CounterState>()
        val store = MviStore(
            initialState = CounterState(4),
            reducer = CounterReducer(),
            parentScope = this,
            stateSaver = saver,
        )

        store.dispatch(CounterIntent.Add(1))
        assertEquals(CounterState(5), saver.saved)
        assertEquals(CounterState(5), MviStore.restore(saver, CounterState()))
        store.close()
    }

    @Test
    fun reentrantEffectDispatchCompletesAfterTheReducerMutexIsReleased() = runTest {
        val store = MviStore(CounterState(), CounterReducer(), this)
        val collector = launch {
            store.effects.collect { effect ->
                if (effect == CounterEffect.Notified) store.dispatch(CounterIntent.Add(1))
            }
        }
        runCurrent()

        store.dispatch(CounterIntent.Notify)
        runCurrent()

        assertEquals(CounterState(1), store.state.value)
        collector.cancel()
        store.close()
    }
}

private data class CounterState(val total: Int = 0) : MviState

private sealed interface CounterIntent : MviIntent {
    data class Add(val value: Int) : CounterIntent
    data object Notify : CounterIntent
    data object Start : CounterIntent
}

private data object CounterAction : MviAction
private data object CounterEffectNotUsed : MviEffect
private sealed interface CounterEffect : MviEffect { data object Notified : CounterEffect }

private class CounterReducer : MviReducer<CounterState, CounterIntent, CounterAction, CounterEffect> {
    override fun reduce(state: CounterState, intent: CounterIntent): MviTransition<CounterState, CounterAction, CounterEffect> =
        when (intent) {
            is CounterIntent.Add -> MviTransition(state.copy(total = state.total + intent.value))
            CounterIntent.Notify -> MviTransition(state, effects = listOf(CounterEffect.Notified))
            CounterIntent.Start -> MviTransition(state, actions = listOf(CounterAction))
        }

}

private class RecordingSaver<State : MviState>(override var saved: State? = null) : MviStateSaver<State>
