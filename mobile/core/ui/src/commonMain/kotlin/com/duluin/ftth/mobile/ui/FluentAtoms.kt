package com.duluin.ftth.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
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
import io.github.composefluent.lightColors
import io.github.composefluent.darkColors
import io.github.composefluent.component.Text

object FluentTokens {
    val pagePadding = 16.dp
    val sectionGap = 12.dp
    val touchTarget = 48.dp
    val surface: Color @Composable get() = LocalFieldPalette.current.surface
    val primary: Color @Composable get() = LocalFieldPalette.current.accent
    val critical: Color @Composable get() = LocalFieldPalette.current.critical
    val muted: Color @Composable get() = LocalFieldPalette.current.muted
}

data class FieldPalette(val plane: Color, val surface: Color, val text: Color, val muted: Color, val accent: Color, val accentInk: Color, val critical: Color)
private val LightFieldPalette = FieldPalette(Color(0xFFF8F9FA), Color.White, Color(0xFF1B1A19), Color(0xFF605E5C), Color(0xFF0078D4), Color.White, Color(0xFFA4262C))
private val DarkFieldPalette = FieldPalette(Color(0xFF1B1A19), Color(0xFF201F1E), Color.White, Color(0xFFC8C6C4), Color(0xFF2899F5), Color(0xFF1B1A19), Color(0xFFFF99A4))
val LocalFieldPalette = staticCompositionLocalOf { LightFieldPalette }

@Composable
fun FieldOperationsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalFieldPalette provides if (dark) DarkFieldPalette else LightFieldPalette) {
        FluentTheme(colors = if (dark) darkColors() else lightColors(), content = content)
    }
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
            .background(if (enabled) FluentTokens.primary else FluentTokens.muted, RoundedCornerShape(4.dp))
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = FluentTokens.pagePadding),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = LocalFieldPalette.current.accentInk) }
}

@Composable
fun FluentMessage(text: String, critical: Boolean = false, modifier: Modifier = Modifier) {
    val semantics = modifier.semantics {
        contentDescription = text
        stateDescription = if (critical) "critical" else "informational"
    }
    if (critical) Text(text, color = FluentTokens.critical, modifier = semantics)
    else Text(text, color = LocalFieldPalette.current.text, modifier = semantics)
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
fun FluentTextInput(label: String, value: String, onValueChange: (String) -> Unit, enabled: Boolean = true,
    password: Boolean = false, quantity: Boolean = false) {
    val palette = LocalFieldPalette.current
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        Text(label, color = palette.text)
        BasicTextField(value = value, onValueChange = onValueChange, enabled = enabled,
            textStyle = TextStyle(color = palette.text, fontSize = 14.sp),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when { password -> KeyboardType.Password; quantity -> KeyboardType.Decimal; else -> KeyboardType.Text }),
            modifier = Modifier.fillMaxWidth()
            .heightIn(min = FluentTokens.touchTarget).background(palette.surface, RoundedCornerShape(4.dp)).padding(12.dp)
            .semantics { contentDescription = label })
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
