package com.duluin.ftth.mobile.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.duluin.ftth.mobile.domain.FieldEvidence
import com.duluin.ftth.mobile.ui.FluentMessage

internal expect fun decodeFieldImage(bytes: ByteArray): ImageBitmap

@Composable
internal fun FieldEvidencePreview(evidence: FieldEvidence) {
    val decoded = remember(evidence) { runCatching { decodeFieldImage(evidence.bytes) } }
    decoded.onSuccess { bitmap ->
        Image(bitmap, "Foto bukti " + evidence.slot, Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit)
    }.onFailure { FluentMessage("Pratinjau foto tidak dapat dibaca. Pilih foto lain.", critical = true) }
}
