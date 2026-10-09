package com.duluin.ftth.mobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.ui.*

internal fun MaterialUnit.label() = when (this) { MaterialUnit.EA -> "unit"; MaterialUnit.MM -> "meter" }
internal fun FieldWorkState.label() = when (this) {
    FieldWorkState.PENDING -> "Belum selesai"
    FieldWorkState.BLOCKED -> "Terkendala"
    FieldWorkState.COMPLETED -> "Selesai"
    FieldWorkState.CANCELLED -> "Dibatalkan"
}
internal fun stockLabel(stock: FieldStock) = stock.sku.name + " · " + stock.quantity.display(stock.sku.baseUnit) + " " + stock.sku.baseUnit.label() + (stock.serial?.let { " · SN " + it } ?: "")

@Composable
internal fun FieldSection(title: String, content: @Composable () -> Unit) {
    FluentPanel { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { FluentMessage(title); content() } }
}

@Composable
internal fun <T> FieldPage(page: MaterialPage<T>?, busy: Boolean, change: (Int) -> Unit) {
    if (page == null) return
    FluentMessage("Halaman " + (page.page + 1) + " · " + page.totalElements + " data")
    if (page.page > 0) FluentAction("Halaman sebelumnya", { change(page.page - 1) }, !busy)
    if ((page.page.toLong() + 1) * page.size < page.totalElements) FluentAction("Halaman berikutnya", { change(page.page + 1) }, !busy)
}

@Composable
internal fun FieldSearch(label: String, busy: Boolean, load: (String) -> Unit) {
    var search by remember(label) { mutableStateOf("") }
    FluentTextInput(label, search, { search = it }, !busy)
    FluentAction("Cari / muat ulang", { load(search) }, !busy)
}

@Composable
internal fun FieldHome(controller: FieldController, state: FieldScreenState) {
    FieldSection("Pekerjaan dan material Anda") {
        FluentMessage("Pekerjaan: " + (state.work?.totalElements?.toString() ?: "belum dimuat") + " · Posisi material: " + (state.stock?.totalElements?.toString() ?: "belum dimuat"))
        FluentMessage("Material yang diserahkan gudang langsung muncul di Material Saya.")
        FluentAction("Lihat pekerjaan", { controller.navigate(FieldDestination.WORK) }, !state.busy)
        FluentAction("Material Saya", { controller.navigate(FieldDestination.STOCK) }, !state.busy)
        FluentAction("Muat ulang ringkasan", { controller.load() }, !state.busy)
    }
}

@Composable
internal fun FieldWorkList(controller: FieldController, state: FieldScreenState) {
    var query by remember { mutableStateOf("") }
    FieldSearch("Cari kode atau judul pekerjaan", state.busy) { query = it; controller.load(search = it) }
    if (state.work?.items?.isEmpty() == true) FluentMessage("Belum ada pekerjaan yang sesuai.")
    state.work?.items?.forEach { work -> FieldSection(work.code + " · " + work.title) {
        FluentMessage(work.type.name + " · " + work.state.label())
        work.scheduledAt?.let { FluentMessage("Jadwal: " + it) }
        FluentAction("Buka " + work.code, { controller.open(work.id) }, !state.busy)
    } }
    FieldPage(state.work, state.busy) { controller.load(it, query) }
}

@Composable
internal fun FieldStockList(controller: FieldController, state: FieldScreenState) {
    var query by remember { mutableStateOf("") }
    FluentMessage("Material Saya")
    FieldSearch("Cari nama, kode atau serial material", state.busy) { query = it; controller.load(search = it) }
    if (state.stock?.items?.isEmpty() == true) FluentMessage("Belum ada material yang sesuai. Muat ulang setelah serah terima gudang.")
    state.stock?.items?.forEach { stock -> FieldSection(stock.sku.name) {
        FluentMessage(stockLabel(stock)); FluentMessage(stock.locationName + " · " + stock.status)
        stock.mac?.let { FluentMessage("MAC: " + it) }
    } }
    FieldPage(state.stock, state.busy) { controller.load(it, query) }
}

@Composable
internal fun FieldPendingPanel(controller: FieldController, state: FieldScreenState) {
    if (state.pending.isEmpty()) return
    FieldSection("Perintah tersimpan") {
        state.pending.forEach { command ->
            FluentMessage(command.label)
            FluentMessage(when (command.state) {
                SecureDeliveryState.QUEUED -> "Draf terenkripsi, belum terkirim"
                SecureDeliveryState.ATTEMPTED -> "Hasil belum pasti; ulangi untuk memastikan"
                SecureDeliveryState.CONFLICT -> "Sumber berubah; periksa lalu buat draf baru"
                SecureDeliveryState.REJECTED -> "Ditolak server; periksa isian dan akses"
            }, command.state in setOf(SecureDeliveryState.CONFLICT, SecureDeliveryState.REJECTED))
            if (command.state in setOf(SecureDeliveryState.QUEUED, SecureDeliveryState.ATTEMPTED))
                FluentAction("Kirim ulang " + command.label, { controller.retry(command.key) }, !state.busy)
            if (command.state != SecureDeliveryState.ATTEMPTED)
                FluentAction("Hapus draf " + command.label, { controller.discard(command.key) }, !state.busy)
        }
    }
}
