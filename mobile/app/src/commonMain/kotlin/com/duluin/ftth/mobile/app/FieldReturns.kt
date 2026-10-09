package com.duluin.ftth.mobile.app

import androidx.compose.runtime.*
import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.ui.*

private data class ReturnReview(val epoch: Long, val warehouse: FieldWarehouse, val use: FieldMaterialUse, val reason: String)

@Composable
internal fun FieldReturns(controller: FieldController, state: FieldScreenState) {
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    FluentMessage("Retur material")
    FluentAction(if (creating) "Tutup formulir" else "Buat retur", { creating = !creating }, !state.busy)
    if (creating) FieldReturnForm(controller, state) { creating = false }
    FieldSearch("Cari alasan retur", state.busy) { query = it; controller.load(search = it) }
    if (state.returns?.items?.isEmpty() == true) FluentMessage("Belum ada retur yang sesuai.")
    state.returns?.items?.forEach { returned -> FieldSection(returned.skuName) {
        FluentMessage(returned.quantity.display(returned.unit) + " " + returned.unit.label() + " · " + returned.state)
        FluentMessage("Ke " + returned.warehouseName); FluentMessage(returned.reason)
    } }
    FieldPage(state.returns, state.busy) { controller.load(it, query) }
}

@Composable
private fun FieldReturnForm(controller: FieldController, state: FieldScreenState, close: () -> Unit) {
    var stock by remember { mutableStateOf<FieldStock?>(null) }
    var warehouse by remember { mutableStateOf<FieldWarehouse?>(null) }
    var quantity by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<ReturnReview?>(null) }
    val enabled = !state.busy && review == null
    FieldSection("Retur baru") {
        FluentMessage("Pilih material milik Anda dan gudang tujuan. Stok berubah setelah retur diterima gudang.")
        FieldSelector("stok saya", stock, enabled, ::stockLabel, controller.repository::materials) {
            stock = it; quantity = if (it.sku.tracking == MaterialTracking.SERIAL) "1" else ""
        }
        FieldSelector("gudang tujuan", warehouse, enabled, { it.name }, { page, query ->
            controller.repository.warehouses(page, query).let { it.copy(items = it.items.filter { row -> row.active && row.kind == "WAREHOUSE" }) }
        }) { warehouse = it }
        FluentTextInput("Jumlah (" + (stock?.sku?.baseUnit?.label() ?: "unit") + ")", quantity, { quantity = it }, enabled && stock?.sku?.tracking != MaterialTracking.SERIAL, quantity = true)
        FluentTextInput("Alasan retur", reason, { reason = it }, enabled)
        if (review == null) FluentAction("Periksa retur", { controller.action {
            val source = requireNotNull(stock) { "Pilih stok yang akan dikembalikan." }
            val destination = requireNotNull(warehouse) { "Pilih gudang tujuan." }
            val use = FieldMaterialUse(source, MaterialQuantity.measured(quantity, source.sku.baseUnit))
            validateFieldUse(use, controller.repository.auth.capture().account.id)
            require(reason.isNotBlank()) { "Isi alasan retur." }
            review = ReturnReview(requireNotNull(state.epoch), destination, use, reason.trim())
        } }, enabled && state.epoch != null)
        review?.let { draft ->
            FluentMessage("Periksa: " + stockLabel(draft.use.source))
            FluentMessage("Retur " + draft.use.quantity.display(draft.use.source.sku.baseUnit) + " " + draft.use.source.sku.baseUnit.label() + " ke " + draft.warehouse.name)
            FluentMessage(draft.reason)
            FluentAction("Ubah isian", { review = null }, !state.busy)
            FluentAction("Simpan dan kirim retur", { controller.action {
                controller.submit(controller.repository.queueReturn(draft.epoch, draft.warehouse, draft.use, draft.reason)); close()
            } }, !state.busy)
        }
    }
}
