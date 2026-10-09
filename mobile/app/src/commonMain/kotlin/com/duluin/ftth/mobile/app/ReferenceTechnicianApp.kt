package com.duluin.ftth.mobile.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.duluin.ftth.mobile.data.ReferenceTechnicianRepository
import com.duluin.ftth.mobile.domain.FieldEvidence
import com.duluin.ftth.mobile.ui.*

enum class FieldPhotoSource { CAMERA, PICKER }
interface FieldPhotoPort { fun choose(slot: String, source: FieldPhotoSource, result: (Result<FieldEvidence>) -> Unit) }

@Composable
fun ReferenceTechnicianApp(repository: ReferenceTechnicianRepository, photos: FieldPhotoPort) {
    val scope = rememberCoroutineScope()
    val controller = remember(repository) { FieldController(repository, scope) }
    val session by repository.auth.session.collectAsState()
    val state by controller.state.collectAsState()
    LaunchedEffect(session?.identity) { controller.clear(); if (session != null) controller.load() }
    FieldOperationsTheme {
        val palette = LocalFieldPalette.current
        Column(Modifier.fillMaxSize().background(palette.plane).windowInsetsPadding(WindowInsets.safeDrawing)) {
            val account = session
            if (account == null) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FieldLogin(controller)
                }
            } else {
                Row(Modifier.fillMaxWidth().background(palette.surface).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { FluentMessage("Teknisi FTTH"); FluentMessage(account.account.name) }
                    Box(Modifier.width(100.dp)) { FluentAction("Keluar", { controller.action { repository.auth.logout() } }, !state.busy) }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(palette.surface)) {
                    FieldDestination.entries.forEach { destination ->
                        Box(Modifier.heightIn(min = 48.dp).semantics { selected = state.destination == destination }
                            .clickable(enabled = !state.busy, role = Role.Tab) { controller.navigate(destination) }
                            .background(if (state.destination == destination) palette.accent.copy(alpha = .12f) else palette.surface)
                            .padding(16.dp)) { FluentMessage(destination.label) }
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.busy) FluentMessage("Memproses...")
                    state.error?.let { FluentMessage(it, critical = true) }
                    state.notice?.let { FluentMessage(it) }
                    if (!state.busy && !state.snapshotCurrent && (state.work != null || state.stock != null || state.requests != null || state.returns != null))
                        FluentMessage("Data terakhir dimuat. Muat ulang untuk memeriksa kondisi terbaru.")
                    if (state.detail != null) FieldWorkDetailScreen(controller, state, photos)
                    else when (state.destination) {
                        FieldDestination.HOME -> FieldHome(controller, state)
                        FieldDestination.WORK -> FieldWorkList(controller, state)
                        FieldDestination.STOCK -> FieldStockList(controller, state)
                        FieldDestination.REQUEST -> FieldRequests(controller, state)
                        FieldDestination.RETURN -> FieldReturns(controller, state)
                    }
                    FieldPendingPanel(controller, state)
                }
            }
        }
    }
}

@Composable
private fun FieldLogin(controller: FieldController) {
    val state by controller.state.collectAsState()
    var server by remember { mutableStateOf("https://") }; var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }; var otp by remember { mutableStateOf("") }
    FluentMessage("Masuk sebagai teknisi")
    FluentMessage("Gunakan akun tenant untuk melihat pekerjaan dan material Anda.")
    FluentTextInput("Alamat server", server, { server = it }, !state.busy)
    FluentTextInput("Email", email, { email = it }, !state.busy)
    FluentTextInput("Password", password, { password = it }, !state.busy, password = true)
    FluentTextInput("Kode autentikator (jika diaktifkan)", otp, { otp = it }, !state.busy)
    state.error?.let { FluentMessage(it, critical = true) }
    if (state.busy) FluentMessage("Memproses...")
    FluentAction("Masuk", { controller.action { controller.repository.auth.login(server, email, password, otp); password = ""; otp = "" } }, !state.busy && email.isNotBlank() && password.isNotBlank())
}
