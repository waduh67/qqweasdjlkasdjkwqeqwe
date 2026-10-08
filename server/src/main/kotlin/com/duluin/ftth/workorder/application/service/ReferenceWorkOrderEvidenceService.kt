package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.common.storage.DeleteGuard
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.WarehouseOperationReceipt
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.service.referenceTimestamp
import com.duluin.ftth.workorder.adapter.outbound.persistence.ReferenceWorkOrderEvidenceStore
import com.duluin.ftth.workorder.adapter.outbound.persistence.ReferenceWorkOrderStore
import com.duluin.ftth.workorder.application.port.inbound.DownloadedContent
import com.duluin.ftth.workorder.application.port.inbound.ReferenceWorkOrderPhotoInput
import com.duluin.ftth.workorder.application.port.inbound.ReferenceWorkOrderPhotoView
import com.duluin.ftth.workorder.application.port.outbound.EvidenceObjectRegistryRepository
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderEvidenceRepository
import com.duluin.ftth.workorder.domain.model.EvidenceKind
import com.duluin.ftth.workorder.domain.model.WorkOrderEvidence
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.MessageDigest
import java.util.UUID

@Service
@Transactional
class ReferenceWorkOrderEvidenceService(private val orders: ReferenceWorkOrderService, private val store: ReferenceWorkOrderStore,
    private val photos: ReferenceWorkOrderEvidenceStore, private val evidence: WorkOrderEvidenceRepository,
    private val registry: EvidenceObjectRegistryRepository, private val storage: ObjectStorage, private val entityManager: EntityManager) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun upload(id: UUID, revision: Long, slot: String, contentType: String, bytes: ByteArray, key: String): WarehouseOperationReceipt {
        val access = orders.access(key)
        val current = orders.authorized(store.get(id, true), access)
        orders.field(current, access)
        validateImage(contentType, bytes)
        val hash = sha256(bytes)
        val input = ReferenceWorkOrderPhotoInput(revision, slot, contentType, bytes.size.toLong(), hash)
        val canonical = orders.canonical(id, input)
        orders.replay("PHOTO", key, canonical, access)?.let { return it }
        val prior = orders.editable(id, revision, access)
        if (slot !in prior.type.photoSlots) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Pilih slot foto work order")
        val now = referenceTimestamp()
        val photo = WorkOrderEvidence.attach(access.current.fence.identity.tenantId, id, EvidenceKind.OTHER, slot,
            contentType, bytes.size.toLong(), null, null, null, access.current.fence.identity.userId, now, now, hash)
        registry.registerPending(photo.id, photo.storageKey, hash, photo.sizeBytes, contentType, photo.uploadedBy, photo.tenantId)
        rollbackCleanup(photo)
        storage.put(photo.storageKey, contentType, bytes)
        verify(photo)
        evidence.save(photo)
        registry.markCommitted(photo.id, storage.head(photo.tenantId.toString(), photo.storageKey).etag)
        entityManager.flush()
        photos.save(photo, slot, prior.assignmentGeneration, prior.revision + 1)
        val view = prior.copy(revision = prior.revision + 1, lastActivityAt = now)
        store.save(view, false)
        return orders.record("PHOTO", key, canonical, access, id, "WO", view.revision, view, "Foto: $slot", "workorder.order.field", 201)
    }
    fun list(id: UUID): List<ReferenceWorkOrderPhotoView> {
        val access = orders.readAccess()
        val current = orders.authorized(store.get(id), access)
        return photos.list(id, current.assignmentGeneration)
    }
    fun download(id: UUID, photoId: UUID): DownloadedContent {
        val access = orders.readAccess()
        val current = orders.authorized(store.get(id), access)
        if (photos.list(id, current.assignmentGeneration).none { it.id == photoId }) masterFailure(WarehouseErrorCode.NOT_FOUND)
        val photo = evidence.findVisibleById(photoId)?.takeIf { it.workOrderId == id } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        val stored = storage.get(photo.storageKey)
        if (stored.size != photo.sizeBytes || stored.contentType != photo.contentType || sha256(stored.bytes) != photo.sha256)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Bukti foto berubah di penyimpanan")
        return DownloadedContent(photo.contentType, stored.bytes)
    }
    internal fun requireCurrent(id: UUID, generation: Long, slots: List<String>): List<WorkOrderEvidence> {
        val current = photos.list(id, generation).filter { it.current }
        if (current.map { it.slot }.toSet() != slots.toSet()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Lengkapi semua foto wajib")
        return current.map { view ->
            val photo = evidence.findVisibleById(view.id) ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            verify(photo)
            photo
        }
    }
    private fun verify(photo: WorkOrderEvidence) {
        val stored = storage.get(photo.storageKey)
        if (stored.size != photo.sizeBytes || stored.contentType != photo.contentType || sha256(stored.bytes) != photo.sha256)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Bukti foto gagal diverifikasi")
    }
    private fun rollbackCleanup(photo: WorkOrderEvidence) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_ROLLED_BACK) return
                try {
                    val stored = storage.get(photo.storageKey)
                    if (sha256(stored.bytes) != photo.sha256) return
                    val metadata = storage.head(photo.tenantId.toString(), photo.storageKey)
                    if (!storage.deleteIfMatch(photo.tenantId.toString(), photo.storageKey, DeleteGuard(metadata.etag, metadata.version)))
                        logger.warn("Work order photo rollback cleanup deferred: evidenceId={}", photo.id)
                } catch (failure: Exception) {
                    logger.warn("Work order photo rollback cleanup failed: evidenceId={}", photo.id, failure)
                }
            }
        })
    }
    private fun validateImage(contentType: String, bytes: ByteArray) {
        val signatureMatches = when (contentType) {
            "image/png" -> bytes.startsWith(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            "image/jpeg" -> bytes.startsWith(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
            "image/gif" -> bytes.startsWith("GIF87a".toByteArray()) || bytes.startsWith("GIF89a".toByteArray())
            "image/webp" -> bytes.size >= 12 && bytes.startsWith("RIFF".toByteArray()) && bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
            else -> false
        }
        if (bytes.size !in 1..5 * 1024 * 1024 || !signatureMatches)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Gunakan foto PNG, JPEG, GIF, atau WebP maksimal 5 MB")
    }
    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && copyOf(prefix.size).contentEquals(prefix)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
