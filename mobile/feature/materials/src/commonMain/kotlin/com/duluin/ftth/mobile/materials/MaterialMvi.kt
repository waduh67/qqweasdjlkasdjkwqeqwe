package com.duluin.ftth.mobile.materials

import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.mvi.*

data class MaterialEnvironment(val scope: String?, val allowed: Boolean, val readOnly: Boolean, val online: Boolean)
enum class MaterialPhase { READY, LOADING, SENDING }
data class MaterialUseRow(val source: MaterialCustody, val quantity: String = "")
sealed interface MaterialForm {
    val evidence: String
    val reason: String
    data class Use(val rows: List<MaterialUseRow> = emptyList(), override val evidence: String = "", override val reason: String = "") : MaterialForm
    data class Receipt(val issue: MaterialIssue, val lineId: String = "", val accepted: String = "", val missing: String = "0", val rejected: String = "0",
        val observedSerial: String = "", override val evidence: String = "", override val reason: String = "") : MaterialForm
}
data class MaterialUiState(
    val environment: MaterialEnvironment,
    val jobs: MaterialPage<MaterialJob>? = null,
    val workspace: MaterialWorkspace? = null,
    val pending: List<MaterialPending> = emptyList(),
    val form: MaterialForm? = null,
    val phase: MaterialPhase = MaterialPhase.READY,
    val message: String? = null,
    val error: String? = null,
) : MviState {
    val canEdit get() = environment.allowed && !environment.readOnly && phase == MaterialPhase.READY
}
sealed interface MaterialIntent : MviIntent {
    data class Environment(val value: MaterialEnvironment) : MaterialIntent
    data class Jobs(val page: Int = 0) : MaterialIntent
    data class Open(val id: String, val issuePage: Int = 0, val custodyPage: Int = 0) : MaterialIntent
    data class CustodyPage(val page: Int) : MaterialIntent
    data class LoadedJobs(val scope: String?, val jobs: MaterialPage<MaterialJob>, val pending: List<MaterialPending>) : MaterialIntent
    data class LoadedWorkspace(val scope: String?, val workspace: MaterialWorkspace, val pending: List<MaterialPending>) : MaterialIntent
    data class Edit(val form: MaterialForm?) : MaterialIntent
    data object Submit : MaterialIntent
    data class Retry(val key: String) : MaterialIntent
    data class Discard(val key: String) : MaterialIntent
    data class Delivered(val scope: String?, val delivery: MaterialDelivery, val pending: List<MaterialPending>) : MaterialIntent
    data class QueueLoaded(val scope: String?, val pending: List<MaterialPending>) : MaterialIntent
    data class QueueRestored(val scope: String?, val pending: List<MaterialPending>) : MaterialIntent
    data class Failed(val scope: String?, val message: String) : MaterialIntent
}
sealed interface MaterialAction : MviAction {
    data class Jobs(val page: Int, val scope: String?) : MaterialAction
    data class Open(val id: String, val issuePage: Int, val custodyPage: Int, val scope: String?) : MaterialAction
    data class Submit(val workspace: MaterialWorkspace, val form: MaterialForm, val scope: String?) : MaterialAction
    data class Retry(val key: String, val scope: String?) : MaterialAction
    data class Discard(val key: String, val scope: String?) : MaterialAction
}
sealed interface MaterialEffect : MviEffect

class MaterialReducer : MviReducer<MaterialUiState, MaterialIntent, MaterialAction, MaterialEffect> {
    override fun reduce(state: MaterialUiState, intent: MaterialIntent): MviTransition<MaterialUiState, MaterialAction, MaterialEffect> {
        val scope = state.environment.scope
        fun same(nextScope: String?) = scope == nextScope && state.environment.allowed
        fun action(action: MaterialAction, phase: MaterialPhase) = MviTransition<MaterialUiState, MaterialAction, MaterialEffect>(state.copy(phase = phase, error = null), listOf(action))
        return when (intent) {
            is MaterialIntent.Environment -> MviTransition(if (scope != intent.value.scope || !intent.value.allowed) MaterialUiState(intent.value) else state.copy(environment = intent.value))
            is MaterialIntent.Jobs -> if (state.environment.allowed && state.environment.online && state.phase == MaterialPhase.READY && state.form == null) action(MaterialAction.Jobs(intent.page, scope), MaterialPhase.LOADING) else MviTransition(state)
            is MaterialIntent.Open -> if (state.environment.allowed && state.environment.online && state.phase == MaterialPhase.READY && state.form == null) action(MaterialAction.Open(intent.id, intent.issuePage, intent.custodyPage, scope), MaterialPhase.LOADING) else MviTransition(state)
            is MaterialIntent.CustodyPage -> if (state.canEdit && state.environment.online && state.workspace != null && state.form is MaterialForm.Use) action(MaterialAction.Open(state.workspace.context.id, state.workspace.issues.page, intent.page, scope), MaterialPhase.LOADING) else MviTransition(state)
            is MaterialIntent.LoadedJobs -> MviTransition(if (same(intent.scope)) state.copy(jobs = intent.jobs, workspace = null, pending = intent.pending, form = null, phase = MaterialPhase.READY) else state)
            is MaterialIntent.LoadedWorkspace -> MviTransition(if (same(intent.scope)) state.copy(workspace = intent.workspace, pending = intent.pending, phase = MaterialPhase.READY) else state)
            is MaterialIntent.Edit -> MviTransition(if (state.canEdit || (intent.form == null && state.phase == MaterialPhase.READY)) state.copy(form = intent.form, error = null, message = null) else state)
            MaterialIntent.Submit -> if (state.canEdit && state.form != null && state.workspace != null) action(MaterialAction.Submit(state.workspace, state.form, scope), MaterialPhase.SENDING) else MviTransition(state)
            is MaterialIntent.Retry -> if (state.canEdit && state.form == null && state.environment.online && state.pending.any { it.key == intent.key && it.state in setOf(SecureDeliveryState.QUEUED, SecureDeliveryState.ATTEMPTED) }) action(MaterialAction.Retry(intent.key, scope), MaterialPhase.SENDING) else MviTransition(state)
            is MaterialIntent.Discard -> if (state.canEdit && state.form == null && state.pending.any { it.key == intent.key && it.state != SecureDeliveryState.ATTEMPTED }) action(MaterialAction.Discard(intent.key, scope), MaterialPhase.SENDING) else MviTransition(state)
            is MaterialIntent.QueueLoaded -> MviTransition(if (same(intent.scope)) state.copy(pending = intent.pending, phase = MaterialPhase.READY) else state)
            is MaterialIntent.QueueRestored -> MviTransition(if (same(intent.scope)) state.copy(pending = intent.pending) else state)
            is MaterialIntent.Failed -> MviTransition(if (same(intent.scope)) state.copy(error = intent.message, phase = MaterialPhase.READY) else state)
            is MaterialIntent.Delivered -> if (!same(intent.scope)) MviTransition(state) else {
                val delivery = intent.delivery
                val next = state.copy(pending = intent.pending, form = if (delivery is MaterialDelivery.Rejected && delivery.key == null) state.form else null, phase = MaterialPhase.READY,
                    message = when (delivery) {
                        is MaterialDelivery.Accepted -> "Transaksi diterima server. Memuat ulang jumlah material."
                        is MaterialDelivery.Pending -> if (delivery.operation.state == SecureDeliveryState.ATTEMPTED) "Hasil belum pasti. Periksa lagi dengan transaksi yang sama." else "Tersimpan di antrean terenkripsi. Belum dikirim dan belum mengubah stok server."
                        is MaterialDelivery.Conflict -> delivery.message
                        is MaterialDelivery.Rejected -> delivery.message
                    })
                if (delivery is MaterialDelivery.Accepted && state.workspace != null) MviTransition(next.copy(phase = MaterialPhase.LOADING), listOf(MaterialAction.Open(state.workspace.context.id, 0, 0, scope))) else MviTransition(next)
            }
        }
    }
}
