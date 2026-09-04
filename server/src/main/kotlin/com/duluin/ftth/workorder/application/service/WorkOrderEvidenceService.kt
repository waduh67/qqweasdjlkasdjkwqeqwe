package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.areaScope
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.workorder.application.port.inbound.AttachEvidenceCommand
import com.duluin.ftth.workorder.application.port.inbound.CaptureSignatureCommand
import com.duluin.ftth.workorder.application.port.inbound.DownloadedContent
import com.duluin.ftth.workorder.application.port.inbound.EvidenceView
import com.duluin.ftth.workorder.application.port.inbound.ManageWorkOrderEvidenceUseCase
import com.duluin.ftth.workorder.application.port.inbound.SignatureView
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderEvidenceQuery
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderEvidenceRepository
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderSignatureRepository
import com.duluin.ftth.workorder.application.port.outbound.EvidenceObjectRegistryRepository
import com.duluin.ftth.workorder.domain.model.WorkOrder
import com.duluin.ftth.workorder.domain.model.WorkOrderEvidence
import com.duluin.ftth.workorder.domain.model.WorkOrderSignature
import com.duluin.ftth.workorder.domain.model.WorkOrderStatus
import com.duluin.ftth.workorder.domain.model.EvidenceRevisionState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.security.MessageDigest

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
    private val registry: EvidenceObjectRegistryRepository,
) : ManageWorkOrderEvidenceUseCase, WorkOrderEvidenceQuery {

    @Transactional
    override fun attachPhoto(workOrderId: UUID, command: AttachEvidenceCommand): EvidenceView {
        val workOrder = requireDocumentable(workOrderId)
        requireFieldAccess(workOrder)
        validateImage(command.contentType, command.bytes)
        val receiptAt = java.time.Instant.now()
        val hash = sha256(command.bytes)
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
            at = receiptAt,
            receiptAt = receiptAt,
            sha256 = hash,
            correctionReason = command.correctionReason,
        )
        registry.registerPending(photo.id, photo.storageKey, hash, photo.expectedSizeBytes, photo.expectedContentType, photo.uploadedBy, photo.tenantId)
        storage.put(photo.storageKey, photo.contentType, command.bytes)
        verifyStored(photo.tenantId, photo.storageKey, photo.expectedSizeBytes, hash)
        val saved = evidence.save(photo)
        registry.markCommitted(photo.id, storage.head(photo.tenantId.toString(), photo.storageKey).etag)
        return saved.toView(uploaderName(photo.uploadedBy))
    }

    @Transactional
    override fun removePhoto(workOrderId: UUID, evidenceId: UUID) {
        val photo = evidence.findById(evidenceId)?.takeIf { it.workOrderId == workOrderId }
            ?: throw NotFoundException("Bukti $evidenceId tidak ditemukan pada work order $workOrderId")
        val workOrder = require(workOrderId)
        requireFieldAccess(workOrder)
        rejectApproved(workOrder)
        evidence.save(photo.rehydrateState(EvidenceRevisionState.TOMBSTONED, "deleted"))
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

        rejectApproved(workOrder)
        val previous = signatures.findByWorkOrder(workOrderId)
        if (previous != null && command.correctionReason.isNullOrBlank()) {
            throw ValidationException("Alasan koreksi tanda tangan wajib diisi")
        }
        previous?.let { signatures.save(it.rehydrateState(EvidenceRevisionState.SUPERSEDED, command.correctionReason)) }
        val receiptAt = java.time.Instant.now()
        val hash = sha256(command.bytes)
        val signature = WorkOrderSignature.capture(
            tenantId = workOrder.tenantId,
            workOrderId = workOrder.id,
            signerName = command.signerName,
            contentType = command.contentType,
            sizeBytes = command.bytes.size.toLong(),
            signedBy = currentUser.current().userId,
            signedAt = command.signedAt ?: java.time.Instant.now(),
            at = receiptAt,
            receiptAt = receiptAt,
            sha256 = hash,
            correctionReason = command.correctionReason,
        )
        registry.registerPending(signature.id, signature.storageKey, hash, signature.expectedSizeBytes, signature.expectedContentType, signature.signedBy, signature.tenantId)
        storage.put(signature.storageKey, signature.contentType, command.bytes)
        verifyStored(signature.tenantId, signature.storageKey, signature.expectedSizeBytes, hash)
        val saved = signatures.save(signature)
        registry.markCommitted(signature.id, storage.head(signature.tenantId.toString(), signature.storageKey).etag)
        return saved.toView(uploaderName(signature.signedBy))
    }

    @Transactional
    override fun removeSignature(workOrderId: UUID) {
        val signature = signatures.findByWorkOrder(workOrderId)
            ?: throw NotFoundException("Work order $workOrderId belum punya tanda tangan")
        val workOrder = require(workOrderId)
        requireFieldAccess(workOrder)
        rejectApproved(workOrder)
        signatures.save(signature.rehydrateState(EvidenceRevisionState.TOMBSTONED, "deleted"))
    }

    override fun listPhotos(workOrderId: UUID): List<EvidenceView> {
        val workOrder = require(workOrderId)
        requireReadAccess(workOrder)
        val photos = evidence.listByWorkOrder(workOrderId)
        val names = iamApi.usersByIds(photos.mapTo(HashSet()) { it.uploadedBy }).associate { it.id to it.name }
        return photos.map { it.toView(names[it.uploadedBy]) }
    }

    override fun getSignature(workOrderId: UUID): SignatureView? {
        val workOrder = require(workOrderId)
        requireReadAccess(workOrder)
        return signatures.findByWorkOrder(workOrderId)?.let { it.toView(uploaderName(it.signedBy)) }
    }

    override fun downloadPhoto(workOrderId: UUID, evidenceId: UUID): DownloadedContent {
        val workOrder = require(workOrderId)
        requireReadAccess(workOrder)
        val photo = evidence.findById(evidenceId)?.takeIf { it.workOrderId == workOrderId }
            ?: throw NotFoundException("Bukti $evidenceId tidak ditemukan pada work order $workOrderId")
        val stored = storage.get(photo.storageKey)
        verifyHash(photo.sha256, stored.bytes)
        return DownloadedContent(safeContentType(photo.contentType, stored.bytes), stored.bytes)
    }

    override fun downloadSignature(workOrderId: UUID): DownloadedContent {
        val workOrder = require(workOrderId)
        requireReadAccess(workOrder)
        val signature = signatures.findByWorkOrder(workOrderId)
            ?: throw NotFoundException("Work order $workOrderId belum punya tanda tangan")
        val stored = storage.get(signature.storageKey)
        verifyHash(signature.sha256, stored.bytes)
        return DownloadedContent(safeContentType(signature.contentType, stored.bytes), stored.bytes)
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
        requireActiveActor()
        requireArea(workOrder)
        if (actor.hasPermission("workorder.evidence.manage")) return
        if (!actor.hasPermission("workorder.order.field") || iamApi.findUser(actor.userId)?.technician != true || !workOrder.isAssignedTo(actor.userId)) {
            throw AccessDeniedException("Work order ${workOrder.code} tidak ditugaskan ke Anda")
        }
    }

    private fun requireReadAccess(workOrder: WorkOrder) {
        requireActiveActor()
        requireArea(workOrder)
        val actor = currentUser.current()
        if (actor.hasPermission("workorder.evidence.view")) return
        if (actor.hasPermission("workorder.order.field") && iamApi.findUser(actor.userId)?.technician == true && workOrder.isAssignedTo(actor.userId)) return
        throw NotFoundException("Work order ${workOrder.id} tidak ditemukan")
    }

    private fun requireActiveActor() {
        if (iamApi.findUser(currentUser.current().userId)?.active != true) throw AccessDeniedException("Akun tidak aktif")
    }

    private fun requireArea(workOrder: WorkOrder) {
        val scope = currentUser.current().areaScope()
        if (scope != null && (workOrder.areaId == null || workOrder.areaId !in scope)) {
            throw AccessDeniedException("Work order di luar area Anda")
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
        val known = when {
            contentType == "image/png" -> bytes.startsWith(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            contentType == "image/jpeg" -> bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            contentType == "image/gif" -> bytes.startsWith("GIF".toByteArray())
            contentType == "image/webp" -> bytes.size > 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) && bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
            else -> false
        }
        if (!known) throw ValidationException("Isi berkas tidak cocok dengan tipe gambar")
    }

    private fun verifyStored(tenantId: UUID, key: String, size: Long, expectedHash: String) {
        val metadata = storage.head(tenantId.toString(), key)
        if (metadata.size != size || (metadata.sha256 != null && metadata.sha256 != expectedHash)) {
            throw ValidationException("Objek storage gagal verifikasi hash/ukuran")
        }
    }

    private fun verifyHash(expected: String?, bytes: ByteArray) {
        if (expected != null && expected != sha256(bytes)) throw ConflictException("Bukti tidak lolos verifikasi integritas")
    }

    private fun safeContentType(declared: String, bytes: ByteArray): String = when {
        bytes.startsWith(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
        bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> "image/jpeg"
        bytes.startsWith("GIF".toByteArray()) -> "image/gif"
        else -> declared
    }

    private fun rejectApproved(workOrder: WorkOrder) {
        if (workOrder.approvalStatus == com.duluin.ftth.workorder.domain.model.WorkOrderApprovalStatus.APPROVED) {
            throw ConflictException("Bukti yang sudah disetujui terkunci")
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && copyOf(prefix.size).contentEquals(prefix)

    private fun WorkOrderEvidence.rehydrateState(state: EvidenceRevisionState, reason: String?) = WorkOrderEvidence.rehydrate(
        id, tenantId, workOrderId, kind, caption, storageKey, contentType, sizeBytes, latitude, longitude,
        capturedAt, uploadedBy, createdAt, receiptAt, sha256, expectedContentType, expectedSizeBytes, state, reason,
    )

    private fun WorkOrderSignature.rehydrateState(state: EvidenceRevisionState, reason: String?) = WorkOrderSignature.rehydrate(
        id, tenantId, workOrderId, signerName, storageKey, contentType, sizeBytes, signedBy, signedAt, createdAt,
        receiptAt, sha256, expectedContentType, expectedSizeBytes, state, reason,
    )

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
