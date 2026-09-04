package com.duluin.ftth.workorder.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.time.Instant
import java.util.UUID

/** Jenis bukti pengerjaan yang lazim di lapangan FTTH. */
enum class EvidenceKind {
    /** Kondisi sebelum dikerjakan. */
    BEFORE,

    /** Hasil setelah dikerjakan. */
    AFTER,

    /** Foto lokasi/rumah pelanggan. */
    LOCATION,

    /** Label/serial number ONU atau perangkat. */
    SERIAL,

    OTHER,
}

enum class EvidenceRevisionState { PENDING, COMMITTED, ORPHAN_OBJECT, MISSING_OBJECT, SUPERSEDED, TOMBSTONED, LEGAL_HOLD }

/**
 * Satu bukti foto sebuah work order. Byte-nya tinggal di object storage
 * (MinIO/S3); yang dipersistensi di DB hanyalah metadata + [storageKey] penunjuk.
 *
 * Merupakan agregat kecil tersendiri yang menunjuk [workOrderId] secara polos —
 * seperti [WorkOrderEvent], tidak dimuat ke dalam agregat [WorkOrder] agar
 * memuat work order tidak ikut menyeret seluruh lampirannya.
 *
 * Kunci penyimpanan diturunkan di domain (dari tenant + work order + id) supaya
 * tata letaknya konsisten dan tidak bocor ke lapisan lain sebagai konvensi liar.
 */
class WorkOrderEvidence private constructor(
    val id: UUID,
    val tenantId: UUID,
    val workOrderId: UUID,
    val kind: EvidenceKind,
    val caption: String?,
    val storageKey: String,
    val contentType: String,
    val sizeBytes: Long,
    val latitude: Double?,
    val longitude: Double?,
    val capturedAt: Instant?,
    val uploadedBy: UUID,
    val createdAt: Instant,
    val receiptAt: Instant = createdAt,
    val sha256: String? = null,
    val expectedContentType: String = contentType,
    val expectedSizeBytes: Long = sizeBytes,
    val revisionState: EvidenceRevisionState = EvidenceRevisionState.COMMITTED,
    val correctionReason: String? = null,
) {
    companion object {
        /** Kunci objek: `<tenant>/wo/<workOrder>/evidence/<id>` — terprefiks tenant sebagai lapis pertahanan. */
        internal fun keyOf(tenantId: UUID, workOrderId: UUID, id: UUID) =
            "$tenantId/wo/$workOrderId/evidence/$id"

        @Suppress("LongParameterList")
        fun attach(
            tenantId: UUID,
            workOrderId: UUID,
            kind: EvidenceKind,
            caption: String?,
            contentType: String,
            sizeBytes: Long,
            latitude: Double?,
            longitude: Double?,
            capturedAt: Instant?,
            uploadedBy: UUID,
            at: Instant = Instant.now(),
            receiptAt: Instant = at,
            sha256: String? = null,
            correctionReason: String? = null,
        ): WorkOrderEvidence {
            val id = UuidV7.generate()
            return WorkOrderEvidence(
                id = id,
                tenantId = tenantId,
                workOrderId = workOrderId,
                kind = kind,
                caption = caption?.ifBlank { null },
                storageKey = keyOf(tenantId, workOrderId, id),
                contentType = contentType,
                sizeBytes = sizeBytes,
                latitude = latitude,
                longitude = longitude,
                capturedAt = capturedAt,
                uploadedBy = uploadedBy,
                createdAt = at,
                receiptAt = receiptAt,
                sha256 = sha256,
                correctionReason = correctionReason,
            )
        }

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            workOrderId: UUID,
            kind: EvidenceKind,
            caption: String?,
            storageKey: String,
            contentType: String,
            sizeBytes: Long,
            latitude: Double?,
            longitude: Double?,
            capturedAt: Instant?,
            uploadedBy: UUID,
            createdAt: Instant,
            receiptAt: Instant = createdAt,
            sha256: String? = null,
            expectedContentType: String = contentType,
            expectedSizeBytes: Long = sizeBytes,
            revisionState: EvidenceRevisionState = EvidenceRevisionState.COMMITTED,
            correctionReason: String? = null,
        ) = WorkOrderEvidence(
            id = id, tenantId = tenantId, workOrderId = workOrderId, kind = kind, caption = caption, storageKey = storageKey, contentType = contentType, sizeBytes = sizeBytes,
            latitude = latitude, longitude = longitude, capturedAt = capturedAt, uploadedBy = uploadedBy, createdAt = createdAt, receiptAt = receiptAt, sha256 = sha256,
            expectedContentType = expectedContentType, expectedSizeBytes = expectedSizeBytes, revisionState = revisionState, correctionReason = correctionReason,
        )
    }
}

/**
 * Tanda tangan pelanggan sebagai bukti serah-terima pekerjaan. Satu work order
 * membawa paling banyak satu tanda tangan (sign-off) — menandatangani ulang
 * menggantikan yang lama. Byte-nya tinggal di object storage seperti [WorkOrderEvidence].
 */
class WorkOrderSignature private constructor(
    val id: UUID,
    val tenantId: UUID,
    val workOrderId: UUID,
    val signerName: String,
    val storageKey: String,
    val contentType: String,
    val sizeBytes: Long,
    val signedBy: UUID,
    val signedAt: Instant,
    val createdAt: Instant,
    val receiptAt: Instant = createdAt,
    val sha256: String? = null,
    val expectedContentType: String = contentType,
    val expectedSizeBytes: Long = sizeBytes,
    val revisionState: EvidenceRevisionState = EvidenceRevisionState.COMMITTED,
    val correctionReason: String? = null,
) {
    companion object {
        internal fun keyOf(tenantId: UUID, workOrderId: UUID, id: UUID) =
            "$tenantId/wo/$workOrderId/signature/$id"

        @Suppress("LongParameterList")
        fun capture(
            tenantId: UUID,
            workOrderId: UUID,
            signerName: String,
            contentType: String,
            sizeBytes: Long,
            signedBy: UUID,
            signedAt: Instant = Instant.now(),
            at: Instant = Instant.now(),
            receiptAt: Instant = at,
            sha256: String? = null,
            correctionReason: String? = null,
        ): WorkOrderSignature {
            val id = UuidV7.generate()
            return WorkOrderSignature(
                id = id,
                tenantId = tenantId,
                workOrderId = workOrderId,
                signerName = signerName.trim(),
                storageKey = keyOf(tenantId, workOrderId, id),
                contentType = contentType,
                sizeBytes = sizeBytes,
                signedBy = signedBy,
                signedAt = signedAt,
                createdAt = at,
                receiptAt = receiptAt,
                sha256 = sha256,
                correctionReason = correctionReason,
            )
        }

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            workOrderId: UUID,
            signerName: String,
            storageKey: String,
            contentType: String,
            sizeBytes: Long,
            signedBy: UUID,
            signedAt: Instant,
            createdAt: Instant,
            receiptAt: Instant = createdAt,
            sha256: String? = null,
            expectedContentType: String = contentType,
            expectedSizeBytes: Long = sizeBytes,
            revisionState: EvidenceRevisionState = EvidenceRevisionState.COMMITTED,
            correctionReason: String? = null,
        ) = WorkOrderSignature(
            id = id, tenantId = tenantId, workOrderId = workOrderId, signerName = signerName, storageKey = storageKey, contentType = contentType, sizeBytes = sizeBytes,
            signedBy = signedBy, signedAt = signedAt, createdAt = createdAt, receiptAt = receiptAt, sha256 = sha256, expectedContentType = expectedContentType,
            expectedSizeBytes = expectedSizeBytes, revisionState = revisionState, correctionReason = correctionReason,
        )
    }
}
