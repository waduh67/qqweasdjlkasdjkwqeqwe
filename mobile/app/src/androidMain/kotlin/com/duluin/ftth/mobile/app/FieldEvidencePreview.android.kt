package com.duluin.ftth.mobile.app

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun decodeFieldImage(bytes: ByteArray): ImageBitmap =
    requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Foto tidak dapat dibaca." }.asImageBitmap()
