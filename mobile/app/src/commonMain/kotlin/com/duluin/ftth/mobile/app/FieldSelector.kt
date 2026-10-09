package com.duluin.ftth.mobile.app

import androidx.compose.runtime.*
import com.duluin.ftth.mobile.domain.MaterialPage
import com.duluin.ftth.mobile.ui.*
import kotlinx.coroutines.CancellationException

@Composable
internal fun <T> FieldSelector(label: String, selected: T?, enabled: Boolean, describe: (T) -> String,
    load: suspend (Int, String) -> MaterialPage<T>, choose: (T) -> Unit) {
    var query by remember(label) { mutableStateOf("") }
    var page by remember(label) { mutableStateOf(0) }
    var trigger by remember(label) { mutableStateOf(0) }
    var result by remember(label) { mutableStateOf<MaterialPage<T>?>(null) }
    var error by remember(label) { mutableStateOf<String?>(null) }
    var busy by remember(label) { mutableStateOf(false) }
    LaunchedEffect(label, page, trigger) {
        busy = true; error = null
        try { result = load(page, query) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Pilihan belum dapat dimuat." }
        finally { busy = false }
    }
    FluentMessage(label)
    selected?.let { FluentMessage("Dipilih: " + describe(it)) }
    FluentTextInput("Cari " + label, query, { query = it }, enabled && !busy)
    FluentAction("Cari " + label, { page = 0; trigger++ }, enabled && !busy)
    if (busy) FluentMessage("Memuat pilihan...")
    error?.let { FluentMessage(it, critical = true) }
    if (!busy && result?.items?.isEmpty() == true) FluentMessage("Tidak ada pilihan yang sesuai.")
    result?.items?.forEach { item -> FluentAction("Pilih " + describe(item), { choose(item) }, enabled && !busy) }
    FieldPage(result, !enabled || busy) { page = it }
}
