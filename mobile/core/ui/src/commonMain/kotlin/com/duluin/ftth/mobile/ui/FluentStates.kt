package com.duluin.ftth.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable

sealed interface ScreenContent {
    data object Loading : ScreenContent
    data object Empty : ScreenContent
    data class Error(val message: String) : ScreenContent
    data class Offline(val message: String) : ScreenContent
    data class Conflict(val message: String) : ScreenContent
    data class PermissionDenied(val message: String) : ScreenContent
    data object Content : ScreenContent
}

data class ScreenContentPresentation(
    val message: String,
    val critical: Boolean,
    val retryLabel: String? = null,
)

fun ScreenContent.presentation(): ScreenContentPresentation = when (this) {
    ScreenContent.Loading -> ScreenContentPresentation("Memuat data lapangan...", critical = false)
    ScreenContent.Empty -> ScreenContentPresentation("Belum ada pekerjaan untuk ditampilkan.", critical = false)
    is ScreenContent.Error -> ScreenContentPresentation(message, critical = true, retryLabel = "Coba lagi")
    is ScreenContent.Offline -> ScreenContentPresentation(message, critical = false)
    is ScreenContent.Conflict -> ScreenContentPresentation(message, critical = true, retryLabel = "Muat ulang perubahan")
    is ScreenContent.PermissionDenied -> ScreenContentPresentation(message, critical = true)
    ScreenContent.Content -> ScreenContentPresentation("", critical = false)
}

@Composable
fun FluentList(items: List<String>, item: @Composable (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FluentTokens.sectionGap)) {
        for (value in items) {
            item(value)
        }
    }
}

@Composable
fun FluentStatePanel(
    content: ScreenContent,
    onRetry: (() -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    FluentPanel {
        Column(verticalArrangement = Arrangement.spacedBy(FluentTokens.sectionGap)) {
            val presentation = content.presentation()
            when (content) {
                ScreenContent.Loading, ScreenContent.Empty, is ScreenContent.Offline, is ScreenContent.PermissionDenied ->
                    FluentMessage(presentation.message, critical = presentation.critical)
                is ScreenContent.Error -> {
                    FluentMessage(presentation.message, critical = presentation.critical)
                    onRetry?.let { FluentAction(requireNotNull(presentation.retryLabel), it) }
                }
                is ScreenContent.Conflict -> {
                    FluentMessage(presentation.message, critical = presentation.critical)
                    onRetry?.let { FluentAction(requireNotNull(presentation.retryLabel), it) }
                }
                ScreenContent.Content -> body()
            }
        }
    }
}
