package com.duluin.ftth.mobile.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.duluin.ftth.mobile.ui.FluentAction
import com.duluin.ftth.mobile.ui.FluentFormField
import com.duluin.ftth.mobile.ui.FluentList
import com.duluin.ftth.mobile.ui.FluentMessage
import com.duluin.ftth.mobile.ui.FluentStatePanel
import com.duluin.ftth.mobile.ui.FluentTokens
import com.duluin.ftth.mobile.ui.ScreenContent

@Composable
fun AttendanceScreen(state: AttendanceUiState, onIntent: (AttendanceIntent) -> Unit, modifier: Modifier = Modifier) {
    FluentStatePanel(state.screenContent(), onRetry = { onIntent(AttendanceIntent.Load) }) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FluentTokens.sectionGap)) {
            val snapshot = state.snapshot ?: return@Column
            FluentList(listOfNotNull(snapshot.shift?.let { "Shift: ${it.name} ${it.startsAt}-${it.endsAt}" }, snapshot.leave?.label)) {
                FluentMessage(it)
            }
            FluentFormField("Status kehadiran", snapshot.attendance)
            if (snapshot.periodLocked) FluentMessage("Periode terkunci. Kehadiran hanya dapat dibaca.")
            else when (state.status) {
                AttendanceStatus.Ready -> FluentAction("Check-in", { onIntent(AttendanceIntent.CheckIn) })
                AttendanceStatus.Submitting -> FluentMessage("Mengirim kehadiran...")
                else -> Unit
            }
        }
    }
}

fun AttendanceUiState.screenContent(): ScreenContent = when (val current = status) {
    AttendanceStatus.Loading -> ScreenContent.Loading
    AttendanceStatus.Ready, AttendanceStatus.Submitting -> ScreenContent.Content
    AttendanceStatus.Offline -> ScreenContent.Offline("Perangkat offline. Kehadiran akan dikirim saat koneksi tersedia.")
    AttendanceStatus.PermissionDenied -> ScreenContent.PermissionDenied("Akun ini tidak dapat mengubah kehadiran.")
    is AttendanceStatus.Conflict -> ScreenContent.Conflict(current.message)
    is AttendanceStatus.Error -> ScreenContent.Error(current.message)
}
