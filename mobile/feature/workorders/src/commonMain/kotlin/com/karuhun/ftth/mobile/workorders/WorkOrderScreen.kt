package com.karuhun.ftth.mobile.workorders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.karuhun.ftth.mobile.domain.WorkOrderState
import com.karuhun.ftth.mobile.ui.FluentAction
import com.karuhun.ftth.mobile.ui.FluentMessage
import com.karuhun.ftth.mobile.ui.FluentStatePanel
import com.karuhun.ftth.mobile.ui.FluentTokens
import com.karuhun.ftth.mobile.ui.ScreenContent

@Composable
fun WorkOrderScreen(
    state: WorkOrderUiState,
    onIntent: (WorkOrderIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    FluentStatePanel(content = state.screenContent(), onRetry = { onIntent(WorkOrderIntent.Load) }) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FluentTokens.sectionGap)) {
            FluentMessage("Status sinkronisasi: ${state.outbox.pending} menunggu, ${state.outbox.conflicts} konflik")
            when (state.workOrder) {
                WorkOrderState.Ready -> FluentAction("Check-in", { onIntent(WorkOrderIntent.CheckIn(state.permission)) })
                WorkOrderState.InProgress -> FluentAction("Check-out", { onIntent(WorkOrderIntent.CheckOut) })
                WorkOrderState.Completed -> FluentMessage("Pekerjaan sudah selesai.")
                else -> Unit
            }
        }
    }
}

fun WorkOrderUiState.screenContent(): ScreenContent {
    val status = workOrder
    return when (status) {
        WorkOrderState.Loading -> ScreenContent.Loading
        WorkOrderState.Ready, WorkOrderState.InProgress, WorkOrderState.Completed -> ScreenContent.Content
        WorkOrderState.PermissionRequired -> ScreenContent.PermissionDenied("Izin lokasi diperlukan untuk check-in.")
        is WorkOrderState.Error -> ScreenContent.Error(status.message)
        WorkOrderState.Offline -> ScreenContent.Offline("Perangkat sedang offline. Perubahan akan diantrikan.")
    }
}
