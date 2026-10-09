package com.duluin.ftth.mobile.app

import com.duluin.ftth.mobile.domain.FieldEvidence
import kotlinx.cinterop.*
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSObject
import platform.UIKit.*

@OptIn(ExperimentalForeignApi::class)
internal class IosFieldPhotos(private val host: () -> UIViewController) : FieldPhotoPort {
    private data class Selection(val slot: String, val result: (Result<FieldEvidence>) -> Unit)
    private var selection: Selection? = null
    private val delegate = object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
        override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
            val pending = selection ?: return
            val result = runCatching {
                val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
                    ?: error("Foto belum dapat dibaca.")
                val normalized = image.size.useContents {
                    val scale = minOf(1.0, 2048.0 / maxOf(width, height))
                    val targetWidth = width * scale; val targetHeight = height * scale
                    UIGraphicsBeginImageContextWithOptions(platform.CoreGraphics.CGSizeMake(targetWidth, targetHeight), false, 1.0)
                    try {
                        image.drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
                        requireNotNull(UIGraphicsGetImageFromCurrentImageContext())
                    } finally { UIGraphicsEndImageContext() }
                }
                val data = requireNotNull(UIImageJPEGRepresentation(normalized, 0.88))
                require(data.length <= (5 * 1024 * 1024).toULong()) { "Foto terlalu besar. Pilih foto maksimal 5 MB." }
                FieldEvidence(pending.slot, "image/jpeg", requireNotNull(data.bytes).readBytes(data.length.toInt()))
            }
            finish(picker, pending, result)
        }
        override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
            selection?.let { finish(picker, it, Result.failure(IllegalStateException("Pemilihan foto dibatalkan."))) }
        }
    }
    override fun choose(slot: String, source: FieldPhotoSource, result: (Result<FieldEvidence>) -> Unit) {
        if (selection != null) { result(Result.failure(IllegalStateException("Selesaikan pemilihan foto sebelumnya."))); return }
        runCatching {
            val type = if (source == FieldPhotoSource.CAMERA) UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
                else UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
            require(UIImagePickerController.isSourceTypeAvailable(type)) { "Sumber foto tidak tersedia pada perangkat ini." }
            val controller = UIImagePickerController()
            controller.sourceType = type; controller.delegate = delegate
            controller.modalPresentationStyle = UIModalPresentationFullScreen
            selection = Selection(slot, result)
            host().presentViewController(controller, animated = true, completion = null)
        }.onFailure { selection = null; result(Result.failure(it)) }
    }
    private fun finish(picker: UIImagePickerController, pending: Selection, result: Result<FieldEvidence>) {
        if (selection !== pending) return
        selection = null
        picker.dismissViewControllerAnimated(true) { pending.result(result) }
    }
}
