package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.workorder.application.port.inbound.AttachEvidenceCommand
import com.duluin.ftth.workorder.application.port.inbound.CaptureSignatureCommand
import com.duluin.ftth.workorder.application.port.inbound.DownloadedContent
import com.duluin.ftth.workorder.application.port.inbound.EvidenceView
import com.duluin.ftth.workorder.application.port.inbound.ManageWorkOrderEvidenceUseCase
import com.duluin.ftth.workorder.application.port.inbound.SignatureView
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderEvidenceQuery
import com.duluin.ftth.workorder.application.port.outbound.ObjectStorage
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderEvidenceRepository
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderSignatureRepository
import com.duluin.ftth.workorder.domain.model.WorkOrder
import com.duluin.ftth.workorder.domain.model.WorkOrderEvidence
import com.duluin.ftth.workorder.domain.model.WorkOrderSignature
import com.duluin.ftth.workorder.domain.model.WorkOrderStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Bukti pengerjaan: foto & tanda tangan. Byte disimpan di object storage
 * (MinIO/S3) lewat port [ObjectStorage], metadatanya di DB. Foto & tanda tangan
 * hanya boleh dilampirkan saat pekerjaan benar-benar berlangsung/selesai — bukan
 * pada draft yang belum ditugaskan maupun work order yang batal.
 *
 * Urutan penulisan: taruh byte ke storage lebih dulu, baru simpan metadata dalam
 * transaksi. Bila simpan metadata gagal, objek yatim di storage tak berbahaya dan
 * bisa direkonsiliasi; sebaliknya (metadata ada tapi byte tak pernah tersimpan)
 * akan menyesatkan.
 */
@Service
@Transactional(readOnly = true)
class WorkOrderEvidenceService(
    private val workOrders: WorkOrderRepository,
    private val evidence: WorkOrderEvidenceRepository,
    private val signatures: WorkOrderSignatureRepository,
    private val storage: ObjectStorage,
    private val iamApi: IamApi,
    private val currentUser: CurrentUserProvider,
) : ManageWorkOrderEvidenceUseCase, WorkOrderEvidenceQuery {

    @Transactional
    override fun attachPhoto(workOrderId: UUID, command: AttachEvidenceCommand): EvidenceView {
        val workOrder = requireDocumentable(workOrderId)
        requireFieldAccess(workOrder)
        validateImage(command.contentType, command.bytes)
        val photo = WorkOrderEvidence.attach(
            tenantId = workOrder.tenantId,
            workOrderId = workOrder.id,
            kind = command.kind,
            caption = command.caption,
            contentType = command.contentType,
            sizeBytes = command.bytes.size.toLong(),
            latitude = command.latitude,
            longitude = command.longitude,
            capturedAt = command.capturedAt,
            uploadedBy = currentUser.current().userId,
        )
        storage.put(photo.storageKey, photo.contentType, command.bytes)
        return evidence.save(photo).toView(uploaderName(photo.uploadedBy))
    }

    @Transactional
    override fun removePhoto(workOrderId: UUID, evidenceId: UUID) {
        val photo = evidence.findById(evidenceId)?.takeIf { it.workOrderId == workOrderId }
            ?: throw NotFoundException("Bukti $evidenceId tidak ditemukan pada work order $workOrderId")
        requireFieldAccess(require(workOrderId))
        evidence.deleteById(photo.id)
        storage.delete(photo.storageKey)
    }

    @Transactional
    override fun captureSignature(workOrderId: UUID, command: CaptureSignatureCommand): SignatureView {
        val workOrder = require(workOrderId)
        requireFieldAccess(workOrder)
        if (workOrder.status != WorkOrderStatus.IN_PROGRESS && workOrder.status != WorkOrderStatus.DONE) {
            throw ConflictException("Tanda tangan hanya bisa direkam saat pekerjaan berlangsung atau setelah selesai")
        }
        if (command.signerName.isBlank()) throw ValidationException("Nama penanda tangan wajib diisi")
        validateImage(command.contentType, command.bytes)

        // Satu tanda tangan per work order: yang lama diganti seutuhnya.
        signatures.findByWorkOrder(workOrderId)?.let {
            signatures.deleteById(it.id)
            storage.delete(it.storageKey)
        }
        val signature = WorkOrderSignature.capture(
            tenantId = workOrder.tenantId,
            workOrderId = workOrder.id,
            signerName = command.signerName,
            contentType = command.contentType,
            sizeBytes = command.bytes.size.toLong(),
            signedBy = currentUser.current().userId,
            signedAt = command.signedAt ?: java.time.Instant.now(),
        )
        storage.put(signature.storageKey, signature.contentType, command.bytes)
        return signatures.save(signature).toView(uploaderName(signature.signedBy))
    }

    @Transactional
    override fun removeSignature(workOrderId: UUID) {
        val signature = signatures.findByWorkOrder(workOrderId)
            ?: throw NotFoundException("Work order $workOrderId belum punya tanda tangan")
        requireFieldAccess(require(workOrderId))
        signatures.deleteById(signature.id)
        storage.delete(signature.storageKey)
    }

    override fun listPhotos(workOrderId: UUID): List<EvidenceView> {
        require(workOrderId)
        val photos = evidence.listByWorkOrder(workOrderId)
        val names = iamApi.usersByIds(photos.mapTo(HashSet()) { it.uploadedBy }).associate { it.id to it.name }
        return photos.map { it.toView(names[it.uploadedBy]) }
    }

    override fun getSignature(workOrderId: UUID): SignatureView? {
        require(workOrderId)
        return signatures.findByWorkOrder(workOrderId)?.let { it.toView(uploaderName(it.signedBy)) }
    }

    override fun downloadPhoto(workOrderId: UUID, evidenceId: UUID): DownloadedContent {
        val photo = evidence.findById(evidenceId)?.takeIf { it.workOrderId == workOrderId }
            ?: throw NotFoundException("Bukti $evidenceId tidak ditemukan pada work order $workOrderId")
        val stored = storage.get(photo.storageKey)
        return DownloadedContent(stored.contentType, stored.bytes)
    }

    override fun downloadSignature(workOrderId: UUID): DownloadedContent {
        val signature = signatures.findByWorkOrder(workOrderId)
            ?: throw NotFoundException("Work order $workOrderId belum punya tanda tangan")
        val stored = storage.get(signature.storageKey)
        return DownloadedContent(stored.contentType, stored.bytes)
    }

    private fun require(id: UUID): WorkOrder =
        workOrders.findById(id) ?: throw NotFoundException("Work order $id tidak ditemukan")

    /** Work order yang boleh didokumentasikan: sudah ada pekerjaannya, belum batal. */
    private fun requireDocumentable(id: UUID): WorkOrder {
        val workOrder = require(id)
        if (workOrder.status == WorkOrderStatus.DRAFT) {
            throw ConflictException("Work order masih draft — tugaskan dulu sebelum melampirkan bukti")
        }
        if (workOrder.status == WorkOrderStatus.CANCELLED) {
            throw ConflictException("Work order sudah dibatalkan, tak bisa dilampiri bukti")
        }
        return workOrder
    }

    /**
     * Bukti lapangan dibatasi kepemilikan: pemegang izin dispatcher (`workorder.evidence.manage`)
     * boleh mengelola bukti WO mana pun; teknisi lapangan (hanya izin `field`) hanya WO
     * yang ditugaskan ke dirinya. Platform admin lolos otomatis via [hasPermission].
     */
    private fun requireFieldAccess(workOrder: WorkOrder) {
        val actor = currentUser.current()
        if (!actor.hasPermission("workorder.evidence.manage") && !workOrder.isAssignedTo(actor.userId)) {
            throw AccessDeniedException("Work order ${workOrder.code} tidak ditugaskan ke Anda")
        }
    }

    private fun uploaderName(userId: UUID): String? = iamApi.findUser(userId)?.name

    private fun validateImage(contentType: String, bytes: ByteArray) {
        if (bytes.isEmpty()) throw ValidationException("Berkas kosong")
        if (!contentType.startsWith("image/")) {
            throw ValidationException("Bukti harus berupa gambar, bukan '$contentType'")
        }
        if (bytes.size > MAX_BYTES) {
            throw ValidationException("Berkas melebihi ${MAX_BYTES / (1024 * 1024)} MB")
        }
    }

    private fun WorkOrderEvidence.toView(uploadedByName: String?) = EvidenceView(
        id = id,
        workOrderId = workOrderId,
        kind = kind.name,
        caption = caption,
        contentType = contentType,
        sizeBytes = sizeBytes,
        latitude = latitude,
        longitude = longitude,
        capturedAt = capturedAt,
        uploadedBy = uploadedBy,
        uploadedByName = uploadedByName,
        createdAt = createdAt,
    )

    private fun WorkOrderSignature.toView(signedByName: String?) = SignatureView(
        id = id,
        workOrderId = workOrderId,
        signerName = signerName,
        contentType = contentType,
        sizeBytes = sizeBytes,
        signedBy = signedBy,
        signedByName = signedByName,
        signedAt = signedAt,
        createdAt = createdAt,
    )

    private companion object {
        /** Batas per berkas bukti; sepadan dengan foto ponsel. */
        const val MAX_BYTES = 15L * 1024 * 1024
    }
}
