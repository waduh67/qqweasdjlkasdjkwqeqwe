package com.duluin.ftth.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Text

object FluentTokens {
    val pagePadding = 16.dp
    val sectionGap = 12.dp
    val touchTarget = 48.dp
    val surface = Color(0xFFF7F9FC)
    val primary = Color(0xFF0F6CBD)
    val critical = Color(0xFFC4314B)
    val muted = Color(0xFF5F6B7A)
}

@Composable
fun FieldOperationsTheme(content: @Composable () -> Unit) {
    FluentTheme(content = content)
}

@Composable
fun FluentAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FluentTokens.touchTarget)
            .background(if (enabled) FluentTokens.primary else FluentTokens.muted, RoundedCornerShape(6.dp))
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = FluentTokens.pagePadding),
        contentAlignment = Alignment.Center,
    ) { Text(label) }
}

@Composable
fun FluentMessage(text: String, critical: Boolean = false, modifier: Modifier = Modifier) {
    val semantics = modifier.semantics {
        contentDescription = text
        stateDescription = if (critical) "critical" else "informational"
    }
    if (critical) Text(text, color = FluentTokens.critical, modifier = semantics)
    else Text(text, modifier = semantics)
}

@Composable
fun FluentFormField(
    label: String,
    value: String,
    error: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        FluentMessage(label)
        FluentMessage(value)
        error?.let { FluentMessage(it, critical = true) }
    }
}

@Composable
fun FluentPanel(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FluentTokens.surface, RoundedCornerShape(8.dp))
            .padding(FluentTokens.pagePadding),
    ) { content() }
}

@Composable
fun ResponsiveScaffold(content: @Composable (PaddingValues) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = FluentTokens.pagePadding)) {
        content(PaddingValues(vertical = FluentTokens.sectionGap))
    }
}
