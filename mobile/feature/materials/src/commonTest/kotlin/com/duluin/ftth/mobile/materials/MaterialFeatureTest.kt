package com.duluin.ftth.mobile.materials

import androidx.lifecycle.ViewModelStore
import com.duluin.ftth.mobile.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

internal fun sampleWorkspace(): MaterialWorkspace {
    val context = MaterialContext("wo", "WO-01", 5, true, true, "ASSIGNED", null, MaterialField("plan", 3, MaterialMode.MATERIAL_REQUIRED, "SUBMITTED", 0, null, null, null))
    val sku = MaterialSku("sku", "DROP", "Kabel drop", MaterialTracking.LOT, MaterialUnit.MM)
    val source = MaterialCustody("source", "receipt", "issue", "ISS-01", "line", "plan", "plan-line", sku, null, MaterialQuantity.base("100000"), MaterialUnit.MM, 7, MaterialLocation("field", "FIELD", "Tas teknisi"), null, "REEL-1", true)
    return MaterialWorkspace(context, MaterialPage(emptyList(), 0, 10, 0), MaterialPage(listOf(source), 0, 25, 1))
}
internal class FeatureSessions : MaterialSessionPort {
    override val state = MutableStateFlow<MaterialSession?>(MaterialSession("tenant", OutboxIdentity("user", "device", "session"), true, false))
    override val connectivity = MutableStateFlow(true)
}
internal class FeaturePort : MaterialPort {
    var captured: MaterialDraft? = null
    var changes = 0
    var delayed: CompletableDeferred<MaterialWorkspace>? = null
    var delivery: MaterialDelivery = MaterialDelivery.Pending(MaterialPending("key", "wo", "WO-01", MaterialCommandKind.REPORT_USE, SecureDeliveryState.QUEUED))
    override suspend fun jobs(page: Int) = MaterialPage(listOf(MaterialJob("wo", "WO-01", "2026-09-25T00:00:00Z")), page, 25, 1)
    override suspend fun workspace(workOrderId: String, issuePage: Int, custodyPage: Int) = delayed?.await() ?: sampleWorkspace()
    override suspend fun submit(draft: MaterialDraft): MaterialDelivery { captured = draft; return delivery }
    override suspend fun retry(key: String) = delivery
    override fun pending() = (delivery as? MaterialDelivery.Pending)?.let { listOf(it.operation) }.orEmpty()
    override fun discard(key: String) = Unit
    override fun sessionChanged() { changes++ }
}
@OptIn(ExperimentalCoroutinesApi::class)
class MaterialFeatureTest {
    @Test fun measuredOfflineDraftBecomesPendingWithoutOptimisticStockAndDoubleSubmitIsBlocked() = runTest {
        val sessions = FeatureSessions(); sessions.connectivity.value = false
        val port = FeaturePort(); val feature = MaterialFeature(port, sessions); val workspace = sampleWorkspace()
        val form = MaterialForm.Use(listOf(MaterialUseRow(workspace.custody.items.single(), "82,500")), "Bukti ukur")
        val state = MaterialUiState(feature.environment(), workspace = workspace, form = form)
        val reducer = MaterialReducer(); val transition = reducer.reduce(state, MaterialIntent.Submit)
        assertEquals(MaterialPhase.SENDING, transition.state.phase)
        assertTrue(reducer.reduce(transition.state, MaterialIntent.Submit).actions.isEmpty())
        val intent = assertIs<MaterialIntent.Delivered>(feature.handle(transition.actions.single()))
        val next = reducer.reduce(transition.state, intent).state
        assertEquals("82500", assertIs<MaterialDraft.ReportUse>(port.captured).lines.single().quantityBase.base)
        assertEquals("100000", next.workspace!!.custody.items.single().quantityBase.base)
        assertEquals(SecureDeliveryState.QUEUED, next.pending.single().state)
        assertTrue(next.message!!.contains("Belum dikirim")); assertNull(next.form)
    }
    @Test fun uncertaintyIsNotDiscardableAndOnlyConfirmedServerSuccessReloadsStock() {
        val reducer = MaterialReducer(); val environment = MaterialEnvironment("scope", true, false, true)
        val pending = MaterialPending("same-key", "wo", "WO-01", MaterialCommandKind.REPORT_USE, SecureDeliveryState.ATTEMPTED)
        val state = MaterialUiState(environment, workspace = sampleWorkspace(), pending = listOf(pending))
        assertTrue(reducer.reduce(state, MaterialIntent.Discard("same-key")).actions.isEmpty())
        assertEquals("same-key", assertIs<MaterialAction.Retry>(reducer.reduce(state, MaterialIntent.Retry("same-key")).actions.single()).key)
        val next = reducer.reduce(state, MaterialIntent.Delivered("scope", MaterialDelivery.Accepted("same-key", "usage", 1), emptyList()))
        assertEquals(MaterialPhase.LOADING, next.state.phase); assertIs<MaterialAction.Open>(next.actions.single())
        assertEquals("100000", next.state.workspace!!.custody.items.single().quantityBase.base)
        assertEquals(MaterialPhase.SENDING, reducer.reduce(state.copy(phase = MaterialPhase.SENDING), MaterialIntent.QueueRestored("scope", listOf(pending))).state.phase)
    }
    @Test fun readonlyAndScopeChangesCannotReuseDraftOrOldAsyncResults() {
        val reducer = MaterialReducer(); val state = MaterialUiState(MaterialEnvironment("first", true, false, true), workspace = sampleWorkspace(), form = MaterialForm.Use())
        assertTrue(reducer.reduce(state.copy(environment = state.environment.copy(readOnly = true)), MaterialIntent.Submit).actions.isEmpty())
        val cleared = reducer.reduce(state, MaterialIntent.Environment(MaterialEnvironment("second", true, false, true))).state
        assertNull(cleared.workspace); assertNull(cleared.form); assertTrue(cleared.pending.isEmpty())
        assertNull(reducer.reduce(cleared, MaterialIntent.LoadedWorkspace("first", sampleWorkspace(), emptyList())).state.workspace)
    }
    @Test fun liveViewModelObservesSessionChangeAndIgnoresWorkOrderLoadedForOldUser() = runTest {
        val sessions = FeatureSessions(); val port = FeaturePort(); val delayed = CompletableDeferred<MaterialWorkspace>(); port.delayed = delayed
        val model = MaterialViewModel(MaterialFeature(port, sessions), StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore(); owner.put("materials", model)
        try {
            runCurrent(); model.dispatch(MaterialIntent.Open("wo")); runCurrent()
            assertEquals(MaterialPhase.LOADING, model.state.value.phase)
            sessions.state.value = sessions.state.value!!.copy(identity = OutboxIdentity("new-user", "device", "new-session"))
            runCurrent(); assertNull(model.state.value.workspace); assertEquals(MaterialPhase.READY, model.state.value.phase)
            delayed.complete(sampleWorkspace()); runCurrent()
            assertNull(model.state.value.workspace); assertTrue(port.changes >= 2)
            sessions.state.value = null; runCurrent(); assertFalse(model.state.value.environment.allowed)
        } finally { owner.clear() }
    }
    @Test fun offlineStartupRestoresPendingCommandsWithoutLoadingOrClaimingServerStock() = runTest {
        val sessions = FeatureSessions(); sessions.connectivity.value = false
        val port = FeaturePort(); val model = MaterialViewModel(MaterialFeature(port, sessions), StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore(); owner.put("materials", model)
        try {
            runCurrent()
            assertEquals(SecureDeliveryState.QUEUED, model.state.value.pending.single().state)
            assertNull(model.state.value.workspace); assertNull(model.state.value.jobs)
            assertFalse(model.state.value.environment.online)
        } finally { owner.clear() }
    }
}
