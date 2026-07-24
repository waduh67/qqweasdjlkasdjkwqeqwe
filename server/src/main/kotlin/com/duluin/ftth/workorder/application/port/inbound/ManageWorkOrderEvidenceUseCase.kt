package com.duluin.ftth.workorder.application.port.inbound

import com.duluin.ftth.workorder.domain.model.EvidenceKind
import java.time.Instant
import java.util.UUID

/**
 * Melampirkan & mencabut bukti pengerjaan (foto, tanda tangan) pada work order.
 * Byte diserahkan sebagai [ByteArray]; adapter web-lah yang membaca multipart.
 */
interface ManageWorkOrderEvidenceUseCase {

    fun attachPhoto(workOrderId: UUID, command: AttachEvidenceCommand): EvidenceView

    fun removePhoto(workOrderId: UUID, evidenceId: UUID)

    /** Menyimpan (atau mengganti) tanda tangan pelanggan pada work order. */
    fun captureSignature(workOrderId: UUID, command: CaptureSignatureCommand): SignatureView

    fun removeSignature(workOrderId: UUID)
}

data class AttachEvidenceCommand(
    val kind: EvidenceKind,
    val caption: String?,
    val contentType: String,
    val bytes: ByteArray,
    val latitude: Double?,
    val longitude: Double?,
    val capturedAt: Instant?,
)

data class CaptureSignatureCommand(
    val signerName: String,
    val contentType: String,
    val bytes: ByteArray,
    val signedAt: Instant?,
)
