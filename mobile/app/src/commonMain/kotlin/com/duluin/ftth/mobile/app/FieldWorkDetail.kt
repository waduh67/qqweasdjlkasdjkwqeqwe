package com.duluin.ftth.mobile.app

import androidx.compose.runtime.*
import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.ui.*

@Composable
internal fun FieldWorkDetailScreen(controller: FieldController, state: FieldScreenState, port: FieldPhotoPort) {
    val detail = requireNotNull(state.detail)
    val work = detail.work
    val editable = work.state in setOf(FieldWorkState.PENDING, FieldWorkState.BLOCKED) && !state.busy
    FluentAction("Kembali ke pekerjaan", controller::back, !state.busy)
    FieldSection(work.code + " · " + work.title) {
        FluentMessage(work.type.name + " · " + work.state.label())
        FluentMessage(work.description)
        if (detail.overdue) FluentMessage("Melewati jadwal", critical = true)
        FluentAction("Muat ulang pekerjaan", { controller.open(work.id) }, !state.busy)
    }
    key(work.id, work.assignmentGeneration) {
        FieldEvidenceForm(controller, state, port, editable)
        if (work.state in setOf(FieldWorkState.PENDING, FieldWorkState.BLOCKED)) {
            FieldCompletionForm(controller, state)
            FieldProgressForm(controller, state)
        }
    }
    FieldSection("Riwayat pekerjaan") {
        if (detail.timeline.isEmpty()) FluentMessage("Belum ada aktivitas.")
        detail.timeline.forEach { activity ->
            FluentMessage(activity.action + " · " + activity.actor + " · " + activity.recordedAt)
            if (activity.notes.isNotBlank()) FluentMessage(activity.notes)
        }
    }
}

@Composable
private fun FieldEvidenceForm(controller: FieldController, state: FieldScreenState, port: FieldPhotoPort, editable: Boolean) {
    val work = requireNotNull(state.detail).work
    var selected by remember { mutableStateOf<FieldEvidence?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val locked = state.pending.any { it.kind == FieldCommandKind.PHOTO && it.state in setOf(SecureDeliveryState.QUEUED, SecureDeliveryState.ATTEMPTED) }
    FieldSection("Foto bukti") {
        work.type.photoSlots.forEach { slot ->
            val present = state.photos.any { it.slot == slot && it.current && it.assignmentGeneration == work.assignmentGeneration }
            FluentMessage(slot + if (present) " · sudah diunggah" else " · wajib dilengkapi")
            FieldPhotoSource.entries.forEach { source ->
                FluentAction((if (source == FieldPhotoSource.CAMERA) "Ambil foto " else "Pilih foto ") + slot, {
                    val identity = controller.repository.auth.capture().identity
                    port.choose(slot, source) { result ->
                        val current = controller.state.value.detail?.work
                        if (controller.repository.auth.session.value?.identity == identity && current?.id == work.id && current.assignmentGeneration == work.assignmentGeneration) {
                            result.onSuccess { selected = it; error = null }.onFailure { error = it.message ?: "Foto belum dapat dibaca." }
                        }
                    }
                }, editable && !locked && selected == null)
            }
        }
        error?.let { FluentMessage(it, critical = true) }
        selected?.let { evidence ->
            FluentMessage("Periksa foto " + evidence.slot + " · " + evidence.bytes.size / 1024 + " KB")
            FieldEvidencePreview(evidence)
            FluentAction("Batal memilih foto", { selected = null }, !state.busy)
            FluentAction("Simpan dan unggah foto", { controller.photo(evidence); selected = null }, editable && !locked)
        }
    }
}

private data class CompletionReview(val epoch: Long, val work: FieldWork, val photos: List<FieldPhoto>, val materials: List<FieldMaterialUse>, val notes: String)

@Composable
private fun FieldCompletionForm(controller: FieldController, state: FieldScreenState) {
    val work = requireNotNull(state.detail).work
    var selected by remember { mutableStateOf<FieldStock?>(null) }
    var quantity by remember { mutableStateOf("") }
    var materials by remember { mutableStateOf<List<FieldMaterialUse>>(emptyList()) }
    var notes by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<CompletionReview?>(null) }
    val locked = state.pending.any { it.kind == FieldCommandKind.COMPLETE && it.state in setOf(SecureDeliveryState.QUEUED, SecureDeliveryState.ATTEMPTED) }
    val enabled = !state.busy && review == null && !locked
    FieldSection("Material dan penyelesaian") {
        FluentMessage(if (work.type.materialRequired) "Catat material yang dipakai sebelum menyelesaikan pekerjaan." else "Material opsional untuk pekerjaan ini.")
        FieldSelector("material saya untuk pekerjaan", selected, enabled, ::stockLabel, controller.repository::materials) {
            selected = it; quantity = if (it.sku.tracking == MaterialTracking.SERIAL) "1" else ""
        }
        FluentTextInput("Jumlah dipakai (" + (selected?.sku?.baseUnit?.label() ?: "unit") + ")", quantity, { quantity = it }, enabled && selected?.sku?.tracking != MaterialTracking.SERIAL, quantity = true)
        FluentAction("Tambahkan material", { controller.action {
            val source = requireNotNull(selected) { "Pilih material milik Anda." }
            val use = FieldMaterialUse(source, MaterialQuantity.measured(quantity, source.sku.baseUnit))
            validateFieldUse(use, controller.repository.auth.capture().account.id)
            require(materials.none { it.source.id == source.id }) { "Material sudah ditambahkan. Hapus baris sebelumnya untuk mengubah jumlah." }
            materials = materials + use; selected = null; quantity = ""
        } }, enabled && selected != null)
        materials.forEach { use ->
            FluentMessage(use.source.sku.name + " · " + use.quantity.display(use.source.sku.baseUnit) + " " + use.source.sku.baseUnit.label())
            use.source.serial?.let { FluentMessage("SN " + it) }
            FluentAction("Hapus " + use.source.sku.name, { materials = materials.filterNot { it.source.id == use.source.id } }, enabled)
        }
        FluentTextInput("Catatan penyelesaian", notes, { notes = it }, enabled)
        if (review == null) FluentAction("Periksa penyelesaian", { controller.action {
            val session = controller.repository.auth.capture()
            controller.refreshDetail(work.id)
            val latest = requireNotNull(controller.state.value.detail).work
            val photos = controller.state.value.photos
            validateFieldCompletion(latest, session.account.id, photos, materials)
            review = CompletionReview(requireNotNull(state.epoch), latest, photos.toList(), materials.toList(), notes.trim())
        } }, enabled && state.epoch != null)
        review?.let { draft ->
            FluentMessage("Selesaikan " + draft.work.code + " dengan " + draft.materials.size + " baris material dan foto wajib yang sudah diunggah.")
            FluentMessage(draft.notes)
            FluentAction("Ubah isian penyelesaian", { review = null }, !state.busy)
            FluentAction("Simpan dan selesaikan pekerjaan", { controller.action {
                controller.submit(controller.repository.queueCompletion(draft.epoch, draft.work, draft.photos, draft.materials, draft.notes)); review = null
            } }, !state.busy && !locked)
        }
    }
}

@Composable
private fun FieldProgressForm(controller: FieldController, state: FieldScreenState) {
    val work = requireNotNull(state.detail).work
    var notes by remember { mutableStateOf("") }
    var blocked by remember { mutableStateOf(work.state == FieldWorkState.BLOCKED) }
    var reviewed by remember { mutableStateOf(false) }
    val locked = state.pending.any { it.kind == FieldCommandKind.PROGRESS && it.state in setOf(SecureDeliveryState.QUEUED, SecureDeliveryState.ATTEMPTED) }
    val enabled = !state.busy && !reviewed && !locked
    FieldSection("Laporkan perkembangan") {
        FluentMessage(if (blocked) "Status: terkendala" else "Status: belum selesai")
        FluentAction("Ubah status perkembangan", { blocked = !blocked }, enabled)
        FluentTextInput("Catatan / kendala", notes, { notes = it }, enabled)
        if (!reviewed) FluentAction("Periksa perkembangan", { reviewed = true }, enabled && (!blocked || notes.isNotBlank()))
        else {
            FluentMessage("Perbarui " + work.code + " · " + notes)
            FluentAction("Ubah laporan", { reviewed = false }, !state.busy)
            FluentAction("Simpan perkembangan", { controller.action {
                controller.submit(controller.repository.queueProgress(requireNotNull(state.epoch), work, notes, blocked)); reviewed = false; notes = ""
            } }, !state.busy && !locked && state.epoch != null)
        }
    }
}
