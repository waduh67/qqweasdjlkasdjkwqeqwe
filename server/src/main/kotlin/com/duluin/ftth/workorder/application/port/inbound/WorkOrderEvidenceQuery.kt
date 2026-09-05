package com.duluin.ftth.workorder.application.port.inbound

import java.time.Instant
import java.util.UUID
import com.duluin.ftth.workorder.domain.model.ProofArtifactKind

/** Membaca bukti pengerjaan sebuah work order dan mengalirkan byte-nya. */
interface WorkOrderEvidenceQuery {

    fun listPhotos(workOrderId: UUID): List<EvidenceView>

    fun getSignature(workOrderId: UUID): SignatureView?

    fun proofOfWork(workOrderId: UUID): ProofOfWorkView

    /** Byte satu bukti foto — di-proxy lewat backend agar tetap ter-gate izin & tenant. */
    fun downloadPhoto(workOrderId: UUID, evidenceId: UUID): DownloadedContent

    /** Byte tanda tangan work order. */
    fun downloadSignature(workOrderId: UUID): DownloadedContent
}

data class EvidenceView(
    val revisionId: UUID,
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
    val revisionId: UUID,
    val workOrderId: UUID,
    val signerName: String,
    val contentType: String,
    val sizeBytes: Long,
    val signedBy: UUID,
    val signedByName: String?,
    val signedAt: Instant,
    val createdAt: Instant,
)

data class ProofOfWorkView(
    val revision: String,
    val artifacts: List<ProofArtifactRevisionView>,
)

data class ProofArtifactRevisionView(
    val kind: ProofArtifactKind,
    val revisionId: UUID,
    val label: String,
)

/** Isi biner yang dialirkan ke klien, lengkap dengan tipe kontennya. */
class DownloadedContent(
    val contentType: String,
    val bytes: ByteArray,
)
