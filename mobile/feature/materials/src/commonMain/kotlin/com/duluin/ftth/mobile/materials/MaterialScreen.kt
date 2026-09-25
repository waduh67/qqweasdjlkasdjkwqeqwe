package com.duluin.ftth.mobile.materials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.ui.*

@Composable
fun MaterialScreen(state: MaterialUiState, dispatch: (MaterialIntent) -> Unit) {
    FluentPanel {
        Column(verticalArrangement = Arrangement.spacedBy(FluentTokens.sectionGap)) {
            FluentMessage("Material Saya")
            if (!state.environment.allowed) {
                FluentMessage("Masuk dengan akun teknisi yang mempunyai akses material.", critical = true)
            } else {
                if (!state.environment.online) FluentMessage("Offline. Isian masih draf; antrean belum mengubah stok server. Jumlah yang terlihat adalah catatan terakhir.")
                if (state.environment.readOnly) FluentMessage("Akun baca saja. Transaksi belum dapat dikirim.")
                state.message?.let { FluentMessage(it) }
                state.error?.let { FluentMessage(it, critical = true) }
                if (state.phase != MaterialPhase.READY) FluentMessage(if (state.phase == MaterialPhase.SENDING) "Memeriksa transaksi…" else "Memuat material…")
                if (state.form == null) {
                    FluentAction("Muat material saya", { dispatch(MaterialIntent.Jobs()) }, state.environment.online && state.phase == MaterialPhase.READY)
                    if (state.workspace == null) state.jobs?.let { jobs ->
                        if (jobs.items.isEmpty()) FluentMessage("Belum ada material untuk Anda.")
                        jobs.items.forEach { job -> FluentAction(job.code, { dispatch(MaterialIntent.Open(job.id)) }, state.environment.online && state.phase == MaterialPhase.READY) }
                        Pages(jobs.page, jobs.size, jobs.totalElements, state.environment.online && state.phase == MaterialPhase.READY) { dispatch(MaterialIntent.Jobs(it)) }
                    }
                }
                state.workspace?.let { workspace -> Workspace(state, workspace, dispatch) }
                if (state.form == null) Queue(state, dispatch)
            }
        }
    }
}

@Composable
private fun Workspace(state: MaterialUiState, workspace: MaterialWorkspace, dispatch: (MaterialIntent) -> Unit) {
    val context = workspace.context
    val assigned = context.currentAssignee && context.active
    val field = context.field
    FluentMessage("${context.code} · ${context.technicalState} · QA ${context.qaState ?: "Belum dinilai"}")
    if (!context.currentAssignee) FluentMessage("Anda tidak lagi ditugaskan. Barang di tangan Anda tetap tercatat; pemakaian baru tidak diizinkan.")
    if (state.form == null) {
        FluentMessage("Pengiriman untuk saya")
        if (workspace.issues.items.isEmpty()) FluentMessage("Belum ada pengiriman.")
        workspace.issues.items.forEach { issue ->
            FluentMessage("${issue.code} · ${issue.sender.name} → ${issue.receiver.name} · ${issue.state}")
            issue.lines.forEach { line -> FluentMessage("${line.sku.name} · ${line.serial ?: line.lotCode ?: line.sku.code} · menunggu ${line.remainingBase.display(line.baseUnit)} ${unit(line.baseUnit)}") }
            if (issue.state != "RECEIVED") FluentAction("Terima ${issue.code}", { dispatch(MaterialIntent.Edit(MaterialForm.Receipt(issue))) }, state.canEdit && assigned && issue.workOrderRevision == context.workOrderRevision)
        }
        Pages(workspace.issues.page, workspace.issues.size, workspace.issues.totalElements, state.environment.online && state.phase == MaterialPhase.READY) { dispatch(MaterialIntent.Open(context.id, it, workspace.custody.page)) }
        FluentMessage("Barang di tangan saya")
        if (workspace.custody.items.isEmpty()) FluentMessage("Belum ada barang di tangan Anda.")
        workspace.custody.items.forEach { source ->
            FluentMessage("${source.sku.name} · ${source.serial ?: source.lotCode ?: source.sku.code} · ${source.quantityBase.display(source.baseUnit)} ${unit(source.baseUnit)} · ${source.location.name ?: source.location.code} · ${source.issueCode}")
            if (source.sku.tracking == MaterialTracking.SERIAL) FluentMessage("Pemasangan perangkat dicatat melalui aset pelanggan.")
        }
        Pages(workspace.custody.page, workspace.custody.size, workspace.custody.totalElements, state.environment.online && state.phase == MaterialPhase.READY) { dispatch(MaterialIntent.Open(context.id, workspace.issues.page, it)) }
        if (field?.mode == MaterialMode.MATERIAL_REQUIRED && !field.hasMeasuredMaterials) FluentMessage("Pemasangan perangkat dicatat melalui aset pelanggan dan diperiksa pada QA.")
        if (field?.planState == "SUBMITTED" && assigned && (field.mode == MaterialMode.NONE || field.hasMeasuredMaterials)) FluentAction(if (field.mode == MaterialMode.NONE) "Deklarasikan tanpa material" else "Catat pemakaian", { dispatch(MaterialIntent.Edit(MaterialForm.Use())) }, state.canEdit && (field.mode != MaterialMode.NONE || field.useRevision == 0L))
    } else when (val form = requireNotNull(state.form)) {
        is MaterialForm.Receipt -> Receipt(state, form) { dispatch(MaterialIntent.Edit(it)) }
        is MaterialForm.Use -> {
            Use(state, workspace, form) { dispatch(MaterialIntent.Edit(it)) }
            Pages(workspace.custody.page, workspace.custody.size, workspace.custody.totalElements, state.canEdit && state.environment.online) { dispatch(MaterialIntent.CustodyPage(it)) }
        }
    }
    if (state.form != null) {
        FluentMessage("Periksa jumlah, barang, dan referensi bukti sebelum mengirim. Stok baru berubah setelah diterima server.")
        FluentAction(if (state.environment.online) "Konfirmasi dan kirim" else "Simpan untuk dikirim", { dispatch(MaterialIntent.Submit) }, state.canEdit && assigned)
        FluentAction("Batal mengisi", { dispatch(MaterialIntent.Edit(null)) }, state.phase == MaterialPhase.READY)
    }
}

@Composable
private fun Receipt(state: MaterialUiState, form: MaterialForm.Receipt, edit: (MaterialForm.Receipt) -> Unit) {
    val enabled = state.canEdit
    FluentMessage("Terima ${form.issue.code}")
    form.issue.lines.filter { it.remainingBase.value > 0 }.forEach { line ->
        FluentAction("Pilih ${line.sku.name} · ${line.serial ?: line.lotCode ?: line.sku.code}", { edit(form.copy(lineId = line.id, accepted = "", observedSerial = "")) }, enabled)
    }
    form.issue.lines.singleOrNull { it.id == form.lineId }?.let { line ->
        FluentMessage("Menunggu ${line.remainingBase.display(line.baseUnit)} ${unit(line.baseUnit)}")
        if (line.serial != null) FluentTextInput("Serial fisik perangkat", form.observedSerial, { edit(form.copy(observedSerial = it)) }, enabled)
        FluentTextInput("Jumlah diterima (${unit(line.baseUnit)})", form.accepted, { edit(form.copy(accepted = it)) }, enabled)
        FluentTextInput("Jumlah kurang (${unit(line.baseUnit)})", form.missing, { edit(form.copy(missing = it)) }, enabled)
        FluentTextInput("Jumlah ditolak (${unit(line.baseUnit)})", form.rejected, { edit(form.copy(rejected = it)) }, enabled)
    }
    FluentTextInput("Alasan selisih", form.reason, { edit(form.copy(reason = it)) }, enabled)
    FluentTextInput("Referensi bukti penerimaan", form.evidence, { edit(form.copy(evidence = it)) }, enabled)
}

@Composable
private fun Use(state: MaterialUiState, workspace: MaterialWorkspace, form: MaterialForm.Use, edit: (MaterialForm.Use) -> Unit) {
    val enabled = state.canEdit
    val field = workspace.context.field
    if (field?.mode == MaterialMode.NONE) FluentMessage("WO ini dinyatakan tanpa material. Isi alasan dan bukti.")
    else {
        FluentMessage("Pilih sumber pemakaian yang sudah diterima")
        workspace.custody.items.filter { materialCanUse(workspace.context, it) }.forEach { source ->
            FluentAction("Tambahkan ${source.sku.name} · ${source.lotCode ?: source.sku.code}", { edit(form.copy(rows = form.rows + MaterialUseRow(source))) }, enabled && form.rows.none { it.source.id == source.id } && (field?.latestUsageId == null || form.rows.isEmpty()))
        }
        form.rows.forEachIndexed { index, row ->
            FluentMessage("${row.source.sku.name} · ${row.source.issueCode} · tersedia ${row.source.quantityBase.display(row.source.baseUnit)} ${unit(row.source.baseUnit)}")
            FluentTextInput("Jumlah dipakai ${index + 1} (${unit(row.source.baseUnit)})", row.quantity, { value -> edit(form.copy(rows = form.rows.mapIndexed { i, other -> if (i == index) other.copy(quantity = value) else other })) }, enabled)
            FluentAction("Hapus sumber ${index + 1}", { edit(form.copy(rows = form.rows.filterIndexed { i, _ -> i != index })) }, enabled)
        }
    }
    FluentTextInput("Alasan pemakaian", form.reason, { edit(form.copy(reason = it)) }, enabled)
    FluentTextInput("Referensi bukti pemakaian", form.evidence, { edit(form.copy(evidence = it)) }, enabled)
}

@Composable
private fun Queue(state: MaterialUiState, dispatch: (MaterialIntent) -> Unit) {
    if (state.pending.isNotEmpty()) FluentMessage("Antrean material")
    state.pending.forEach { row ->
        val label = when (row.kind) { MaterialCommandKind.ACKNOWLEDGE -> "Penerimaan"; MaterialCommandKind.REPORT_USE, MaterialCommandKind.CORRECT_USE -> "Pemakaian" }
        FluentMessage("${row.workOrderCode} · $label · " + when (row.state) { SecureDeliveryState.QUEUED -> "Belum dikirim"; SecureDeliveryState.ATTEMPTED -> "Hasil belum pasti"; SecureDeliveryState.CONFLICT -> "Konflik, perlu draf baru"; SecureDeliveryState.REJECTED -> "Ditolak" })
        if (row.state in setOf(SecureDeliveryState.QUEUED, SecureDeliveryState.ATTEMPTED)) FluentAction(if (row.state == SecureDeliveryState.ATTEMPTED) "Periksa transaksi yang sama" else "Kirim antrean", { dispatch(MaterialIntent.Retry(row.key)) }, state.canEdit && state.environment.online)
        if (row.state != SecureDeliveryState.ATTEMPTED) FluentAction("Hapus antrean ${row.workOrderCode}", { dispatch(MaterialIntent.Discard(row.key)) }, state.canEdit)
    }
}

private fun unit(unit: MaterialUnit) = if (unit == MaterialUnit.MM) "m" else "unit"

@Composable
private fun Pages(page: Int, size: Int, total: Long, enabled: Boolean, select: (Int) -> Unit) {
    if (page > 0) FluentAction("Halaman sebelumnya", { select(page - 1) }, enabled)
    if ((page.toLong() + 1) * size < total) FluentAction("Halaman berikutnya", { select(page + 1) }, enabled)
}
