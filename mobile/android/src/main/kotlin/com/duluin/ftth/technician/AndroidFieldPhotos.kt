package com.duluin.ftth.technician

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.exifinterface.media.ExifInterface
import com.duluin.ftth.mobile.app.FieldPhotoPort
import com.duluin.ftth.mobile.app.FieldPhotoSource
import com.duluin.ftth.mobile.domain.FieldEvidence
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AndroidFieldPhotos(private val activity: ComponentActivity) : FieldPhotoPort {
    private data class Selection(val slot: String, val result: (Result<FieldEvidence>) -> Unit, val file: File? = null)
    private var pending: Selection? = null
    private val camera = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val selection = pending ?: return@registerForActivityResult
        if (saved) selection.file?.let { read(Uri.fromFile(it), selection) } else finish(selection, Result.failure(IllegalStateException("Pengambilan foto dibatalkan.")))
    }
    private val picker = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val selection = pending ?: return@registerForActivityResult
        if (uri == null) finish(selection, Result.failure(IllegalStateException("Pemilihan foto dibatalkan."))) else read(uri, selection)
    }
    override fun choose(slot: String, source: FieldPhotoSource, result: (Result<FieldEvidence>) -> Unit) {
        if (pending != null) { result(Result.failure(IllegalStateException("Selesaikan pemilihan foto sebelumnya."))); return }
        try {
            if (source == FieldPhotoSource.CAMERA) {
                val directory = File(activity.cacheDir, "evidence").apply { mkdirs() }
                val file = File.createTempFile("capture-", ".jpg", directory)
                pending = Selection(slot, result, file)
                camera.launch(FileProvider.getUriForFile(activity, activity.packageName + ".photos", file))
            } else { pending = Selection(slot, result); picker.launch("image/*") }
        } catch (failure: Exception) { pending?.let { finish(it, Result.failure(failure)) } ?: result(Result.failure(failure)) }
    }
    private fun read(uri: Uri, selection: Selection) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                activity.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "File bukan gambar yang dapat dibaca." }
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val image = activity.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
                    ?: error("Foto belum dapat dibaca.")
                var oriented = image
                try {
                    val exif = activity.contentResolver.openInputStream(uri).use { input -> ExifInterface(requireNotNull(input)) }
                    val matrix = Matrix().apply {
                        if (exif.isFlipped) postScale(-1f, 1f)
                        postRotate(exif.rotationDegrees.toFloat())
                    }
                    if (!matrix.isIdentity) oriented = Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
                    val bytes = ByteArrayOutputStream().use { output ->
                        check(oriented.compress(Bitmap.CompressFormat.JPEG, 88, output)); output.toByteArray()
                    }
                    FieldEvidence(selection.slot, "image/jpeg", bytes)
                } finally { if (oriented !== image) oriented.recycle(); image.recycle() }
            } }
            finish(selection, result)
        }
    }
    private fun finish(selection: Selection, result: Result<FieldEvidence>) {
        if (pending !== selection) return
        pending = null; selection.file?.delete(); selection.result(result)
    }
    fun close() { pending?.file?.delete(); pending = null }
}
