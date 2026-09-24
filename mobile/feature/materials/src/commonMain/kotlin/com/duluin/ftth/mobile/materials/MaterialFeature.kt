package com.duluin.ftth.mobile.materials

import androidx.lifecycle.viewModelScope
import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.mvi.MviViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MaterialFeature(private val material: MaterialPort, val sessions: MaterialSessionPort) {
    fun environment(): MaterialEnvironment {
        val session = sessions.current()
        val scope = session?.let { "${it.tenantId}:${it.identity.userId}:${it.identity.deviceId}:${it.identity.sessionId}" }
        return MaterialEnvironment(scope, session?.fieldAllowed == true, session?.readOnly != false, sessions.online())
    }
    fun sessionChanged() = material.sessionChanged()
    fun pending() = material.pending()
    suspend fun handle(action: MaterialAction): MaterialIntent {
        val scope = when (action) { is MaterialAction.Jobs -> action.scope; is MaterialAction.Open -> action.scope; is MaterialAction.Submit -> action.scope; is MaterialAction.Retry -> action.scope; is MaterialAction.Discard -> action.scope }
        if (environment().scope != scope) return MaterialIntent.Environment(environment())
        return try {
            when (action) {
                is MaterialAction.Jobs -> MaterialIntent.LoadedJobs(scope, material.jobs(action.page), material.pending())
                is MaterialAction.Open -> MaterialIntent.LoadedWorkspace(scope, material.workspace(action.id, action.issuePage, action.custodyPage), material.pending())
                is MaterialAction.Submit -> {
                    val draft = draft(action.workspace, action.form)
                    val delivery = material.submit(draft)
                    if (environment().scope != scope) MaterialIntent.Environment(environment()) else MaterialIntent.Delivered(scope, delivery, material.pending())
                }
                is MaterialAction.Retry -> {
                    val delivery = material.retry(action.key)
                    if (environment().scope != scope) MaterialIntent.Environment(environment()) else MaterialIntent.Delivered(scope, delivery, material.pending())
                }
                is MaterialAction.Discard -> { material.discard(action.key); MaterialIntent.QueueLoaded(scope, material.pending()) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { MaterialIntent.Failed(scope, failure.message ?: "Material belum dapat diproses.") }
    }
    private fun draft(workspace: MaterialWorkspace, form: MaterialForm): MaterialDraft = when (form) {
        is MaterialForm.Use -> MaterialDraft.ReportUse(workspace.context, form.rows.map { MaterialMeasuredUse(it.source, MaterialQuantity.measured(it.quantity, it.source.baseUnit)) }, form.evidence, form.reason)
        is MaterialForm.Receipt -> {
            val line = form.issue.lines.singleOrNull { it.id == form.lineId } ?: throw IllegalArgumentException("Pilih barang yang diterima.")
            MaterialDraft.Acknowledge(workspace.context, form.issue, listOf(MaterialMeasuredReceipt(line.id, MaterialQuantity.measured(form.accepted, line.baseUnit),
                MaterialQuantity.measured(form.missing, line.baseUnit, zero = true), MaterialQuantity.measured(form.rejected, line.baseUnit, zero = true), form.reason, form.observedSerial)), form.evidence)
        }
    }
}

class MaterialViewModel(feature: MaterialFeature, dispatcher: CoroutineDispatcher = Dispatchers.Default) :
    MviViewModel<MaterialUiState, MaterialIntent, MaterialAction, MaterialEffect>(MaterialUiState(feature.environment()), MaterialReducer(), actionHandler = feature::handle, dispatcher = dispatcher) {
    init {
        viewModelScope.launch(dispatcher) {
            combine(feature.sessions.state, feature.sessions.connectivity) { _, _ -> feature.environment() }.collect { environment ->
                try {
                    feature.sessionChanged(); dispatch(MaterialIntent.Environment(environment))
                    if (environment.allowed) dispatch(MaterialIntent.QueueRestored(environment.scope, feature.pending()))
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { dispatch(MaterialIntent.Environment(environment.copy(allowed = false))) }
            }
        }
    }
}
