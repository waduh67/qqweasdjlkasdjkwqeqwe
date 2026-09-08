package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.storage.StoredObject
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.PostingOperation
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest
import java.util.UUID

@Service
class ReceiptEvidenceService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val scopes: InventoryWarehouseScopeApi, private val masters: WarehouseMasterStore, private val receipts: WarehouseReceiptService,
    private val documents: WarehouseReceiptPersistence, private val evidence: ReceiptEvidencePersistence,
    private val operations: WarehouseOperationStore, private val storage: ObjectStorage) {
    private val mapper = jacksonObjectMapper()

    fun upload(document: UUID, revision: Long, key: String, contentType: String, bytes: ByteArray): WarehouseOperationReceipt {
        receiptKey(key)
        if (revision !in 0 until Long.MAX_VALUE || bytes.isEmpty() || bytes.size > 15728640) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val matches = when (contentType) {
            "image/png" -> bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            "image/jpeg" -> bytes.take(3).toByteArray().contentEquals(byteArrayOf(-1, -40, -1))
            "application/pdf" -> bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())
            else -> false
        }
        if (!matches) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.receipt.manage")
        masters.lockTopology()
        val scope = scopes.currentUnderFence(current.fence)
        val record = documents.get(document, true)
        receipts.authorize(record.intake, current, scope)
        val hash = digest(bytes)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("document" to document, "revision" to revision, "sha256" to hash, "contentType" to contentType)))
        val namespace = "warehouse.receipt.attachment"
        val prior = operations.lockKey(namespace, key)
        if (prior != null) {
            if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.resourceId != document || prior.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            receipts.authorize(mapper.readValue(operations.identity(prior.receipt.operationId), ReceiptIntake::class.java), current, scope)
            return prior.receipt
        }
        if (record.revision != revision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (record.state == WarehouseReceiptState.CLOSED) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val id = UUID.randomUUID()
        val objectKey = "${current.fence.identity.tenantId}/warehouse/receipts/$document/$id"
        val value = StoredReceiptEvidence(ReceiptEvidenceView(id, document, contentType, bytes.size.toLong(), hash), objectKey)
        evidence.save(value, current.fence.identity.userId)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) storage.delete(objectKey)
            }
        })
        storage.put(objectKey, contentType, bytes)
        verified(value)
        documents.advance(document, revision)
        val body = mapper.writeValueAsString(value.view)
        val operation = PostingOperation(UUID.randomUUID(), namespace, key, current.fence.identity.userId, document,
            "receipt:$document", canonical.hash, "ATTACHMENT", 201, body, current.fence.epoch)
        documents.operation(operation, revision + 1, cutover.snapshot.epoch)
        operations.storeIdentity(operation.id, mapper.writeValueAsString(record.intake), current.fence.identity.sessionId)
        return WarehouseOperationReceipt(operation.id, document, revision + 1, 201, body, operation.recordedAt)
    }

    @Transactional
    fun download(document: UUID, id: UUID): StoredObject {
        receipts.get(document)
        return verified(evidence.get(document, id))
    }

    internal fun requireEvidence(document: UUID, id: UUID) { verified(evidence.get(document, id)) }

    private fun verified(value: StoredReceiptEvidence): StoredObject {
        val result = try { storage.get(value.objectKey) } catch (_: NotFoundException) { masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED) }
        if (result.size != value.view.sizeBytes || result.contentType != value.view.contentType || digest(result.bytes) != value.view.sha256)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return result
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
