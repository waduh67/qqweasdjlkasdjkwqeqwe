package com.duluin.ftth.mobile.payroll

import com.duluin.ftth.mobile.domain.Permission
import com.duluin.ftth.mobile.domain.PersonalPayslip
import com.duluin.ftth.mobile.domain.PayslipResult
import com.duluin.ftth.mobile.domain.SecurePayslipPort
import com.duluin.ftth.mobile.mvi.MviAction
import com.duluin.ftth.mobile.mvi.MviEffect
import com.duluin.ftth.mobile.mvi.MviIntent
import com.duluin.ftth.mobile.mvi.MviReducer
import com.duluin.ftth.mobile.mvi.MviState
import com.duluin.ftth.mobile.mvi.MviViewModel
import com.duluin.ftth.mobile.mvi.MviTransition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

sealed interface PayrollStatus {
    data object Loading : PayrollStatus
    data object Ready : PayrollStatus
    data object ReadOnly : PayrollStatus
    data object PermissionDenied : PayrollStatus
    data class Error(val message: String) : PayrollStatus
}

data class PayrollUiState(
    val payslip: PersonalPayslip? = null,
    val status: PayrollStatus = PayrollStatus.Loading,
    val permissions: Set<Permission> = emptySet(),
) : MviState

sealed interface PayrollIntent : MviIntent {
    data object Load : PayrollIntent
    data class Loaded(val payslip: PersonalPayslip) : PayrollIntent
    data object Denied : PayrollIntent
    data class Failed(val message: String) : PayrollIntent
}

data object LoadPayroll : MviAction
sealed interface PayrollEffect : MviEffect

class PayrollReducer : MviReducer<PayrollUiState, PayrollIntent, LoadPayroll, PayrollEffect> {
    override fun reduce(state: PayrollUiState, intent: PayrollIntent): MviTransition<PayrollUiState, LoadPayroll, PayrollEffect> = when (intent) {
        PayrollIntent.Load -> if (Permission.PayslipSelf in state.permissions) MviTransition(state.copy(status = PayrollStatus.Loading), actions = listOf(LoadPayroll)) else MviTransition(state.copy(status = PayrollStatus.PermissionDenied))
        is PayrollIntent.Loaded -> MviTransition(state.copy(payslip = intent.payslip, status = if (intent.payslip.periodLocked) PayrollStatus.ReadOnly else PayrollStatus.Ready))
        PayrollIntent.Denied -> MviTransition(state.copy(status = PayrollStatus.PermissionDenied))
        is PayrollIntent.Failed -> MviTransition(state.copy(status = PayrollStatus.Error(intent.message)))
    }
}

class PayrollFeature(private val payslips: SecurePayslipPort) {
    suspend fun load(): PayrollIntent = payslips.personalPayslip().fold(
        onSuccess = { result -> when (result) {
            is PayslipResult.Available -> PayrollIntent.Loaded(result.payslip)
            PayslipResult.Denied -> PayrollIntent.Denied
        } },
        onFailure = { PayrollIntent.Failed(it.message ?: "Slip gaji tidak dapat dimuat") },
    )
}

class PayrollViewModel(feature: PayrollFeature, permissions: Set<Permission>, dispatcher: CoroutineDispatcher = Dispatchers.Default) : MviViewModel<PayrollUiState, PayrollIntent, LoadPayroll, PayrollEffect>(
        initialState = PayrollUiState(permissions = permissions),
        reducer = PayrollReducer(),
        dispatcher = dispatcher,
        actionHandler = { feature.load() },
    )
