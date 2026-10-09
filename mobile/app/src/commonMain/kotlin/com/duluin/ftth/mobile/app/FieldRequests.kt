package com.duluin.ftth.mobile.app

import androidx.compose.runtime.*
import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.ui.*

private data class RequestReview(val epoch: Long, val sku: MaterialSku?, val name: String, val unit: MaterialUnit,
    val quantity: MaterialQuantity, val reason: String, val procurement: Boolean)

@Composable
internal fun FieldRequests(controller: FieldController, state: FieldScreenState) {
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    FluentMessage("Permintaan material")
    FluentAction(if (creating) "Tutup formulir" else "Buat permintaan", { creating = !creating }, !state.busy)
    if (creating) FieldRequestForm(controller, state) { creating = false }
    FieldSearch("Cari alasan permintaan", state.busy) { query = it; controller.load(search = it) }
    if (state.requests?.items?.isEmpty() == true) FluentMessage("Belum ada permintaan yang sesuai.")
    state.requests?.items?.forEach { request -> FieldSection(request.reason) {
        FluentMessage(request.state)
        request.lines.forEach { line ->
            FluentMessage(line.name)
            FluentMessage("Diminta " + line.requested.display(line.unit) + " · disetujui " + line.approved.display(line.unit) + " · diterima " + line.fulfilled.display(line.unit) + " " + line.unit.label())
        }
    } }
    FieldPage(state.requests, state.busy) { controller.load(it, query) }
}

@Composable
private fun FieldRequestForm(controller: FieldController, state: FieldScreenState, close: () -> Unit) {
    var procurement by remember { mutableStateOf(false) }
    var sku by remember { mutableStateOf<MaterialSku?>(null) }
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(MaterialUnit.EA) }
    var quantity by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<RequestReview?>(null) }
    val enabled = !state.busy && review == null
    FieldSection("Permintaan baru") {
        FluentMessage(if (procurement) "Pengadaan: gudang membeli material" else "Restok: gudang menyerahkan stok tersedia")
        FluentAction(if (procurement) "Pilih restok" else "Pilih pengadaan", { procurement = !procurement }, enabled)
        FieldSelector("material katalog", sku, enabled, { it.code + " · " + it.name }, controller.repository::skus) { sku = it; unit = it.baseUnit }
        if (procurement) {
            FluentAction("Usulkan material baru", { sku = null }, enabled)
            if (sku == null) {
                FluentTextInput("Nama material baru", name, { name = it }, enabled)
                FluentMessage("Satuan: " + unit.label())
                FluentAction("Ubah satuan", { unit = if (unit == MaterialUnit.EA) MaterialUnit.MM else MaterialUnit.EA }, enabled)
            }
        }
        FluentTextInput("Jumlah (" + unit.label() + ")", quantity, { quantity = it }, enabled, quantity = true)
        FluentTextInput("Alasan permintaan", reason, { reason = it }, enabled)
        if (review == null) FluentAction("Periksa permintaan", { controller.action {
            require(reason.isNotBlank() && (sku != null || procurement && name.isNotBlank())) { "Pilih material dan isi alasan." }
            review = RequestReview(requireNotNull(state.epoch), sku, name.trim(), unit, MaterialQuantity.measured(quantity, unit), reason.trim(), procurement)
        } }, enabled && state.epoch != null)
        review?.let { draft ->
            FluentMessage("Periksa: " + (draft.sku?.name ?: draft.name) + " · " + draft.quantity.display(draft.unit) + " " + draft.unit.label())
            FluentMessage(draft.reason)
            FluentAction("Ubah isian", { review = null }, !state.busy)
            FluentAction("Simpan dan kirim permintaan", { controller.action {
                controller.submit(controller.repository.queueRequest(draft.epoch, draft.sku, draft.name, draft.unit, draft.quantity, draft.reason, draft.procurement)); close()
            } }, !state.busy)
        }
    }
}
