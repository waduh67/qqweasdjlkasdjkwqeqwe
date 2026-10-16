package com.karuhun.ftth.mobile.payroll

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.karuhun.ftth.mobile.ui.FluentFormField
import com.karuhun.ftth.mobile.ui.FluentList
import com.karuhun.ftth.mobile.ui.FluentMessage
import com.karuhun.ftth.mobile.ui.FluentStatePanel
import com.karuhun.ftth.mobile.ui.FluentTokens
import com.karuhun.ftth.mobile.ui.ScreenContent

@Composable
fun PayrollScreen(state: PayrollUiState, onIntent: (PayrollIntent) -> Unit, modifier: Modifier = Modifier) {
    FluentStatePanel(state.screenContent(), onRetry = { onIntent(PayrollIntent.Load) }) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FluentTokens.sectionGap)) {
            val payslip = state.payslip ?: return@Column
            FluentFormField("Slip gaji", payslip.period)
            FluentFormField("Gaji bersih", "${payslip.currency} ${payslip.netMinor}")
            FluentList(payslip.lines.map { "${it.label}: ${it.amountMinor}" }) { FluentMessage(it) }
            if (state.status == PayrollStatus.ReadOnly) FluentMessage("Periode terkunci. Slip gaji hanya dapat dibaca.")
        }
    }
}

fun PayrollUiState.screenContent(): ScreenContent = when (val current = status) {
    PayrollStatus.Loading -> ScreenContent.Loading
    PayrollStatus.Ready, PayrollStatus.ReadOnly -> ScreenContent.Content
    PayrollStatus.PermissionDenied -> ScreenContent.PermissionDenied("Akun ini hanya dapat melihat slip gaji pribadi.")
    is PayrollStatus.Error -> ScreenContent.Error(current.message)
}
