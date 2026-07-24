package com.duluin.ftth.workorder.application.port.inbound

import java.time.Instant
import java.util.UUID

/** Membaca bukti pengerjaan sebuah work order dan mengalirkan byte-nya. */
interface WorkOrderEvidenceQuery {

    fun listPhotos(workOrderId: UUID): List<EvidenceView>

    fun getSignature(workOrderId: UUID): SignatureView?

    /** Byte satu bukti foto — di-proxy lewat backend agar tetap ter-gate izin & tenant. */
    fun downloadPhoto(workOrderId: UUID, evidenceId: UUID): DownloadedContent

    /** Byte tanda tangan work order. */
    fun downloadSignature(workOrderId: UUID): DownloadedContent
}

data class EvidenceView(
    val id: UUID,
    val workOrderId: UUID,
    val kind: String,
    val caption: String?,
    val contentType: String,
    val sizeBytes: Long,
    val latitude: Double?,
    val longitude: Double?,
    val capturedAt: Instant?,
    val uploadedBy: UUID,
    /** Nama pengunggah, diresolusi lewat iam; `null` bila penggunanya tak ada lagi. */
    val uploadedByName: String?,
    val createdAt: Instant,
)

data class SignatureView(
    val id: UUID,
    val workOrderId: UUID,
    val signerName: String,
    val contentType: String,
    val sizeBytes: Long,
    val signedBy: UUID,
    val signedByName: String?,
    val signedAt: Instant,
    val createdAt: Instant,
)

/** Isi biner yang dialirkan ke klien, lengkap dengan tipe kontennya. */
class DownloadedContent(
    val contentType: String,
    val bytes: ByteArray,
)
