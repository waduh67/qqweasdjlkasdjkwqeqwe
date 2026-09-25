package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.storage.StoredObject
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.MigrationEvidenceStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.StoredMigrationEvidence
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehousePolicyPersistence
import com.duluin.ftth.inventory.application.port.inbound.WarehouseMigrationEvidenceInput
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class MigrationEvidenceService(private val cutovers: InventoryTenantCutoverApi, private val access: WarehouseProvenanceAccess,
    private val store: MigrationEvidenceStore, private val clock: WarehousePolicyPersistence, private val storage: ObjectStorage,
    private val reconciliation: MigrationEvidenceReconciler) {
    private val mapper = jacksonObjectMapper()

    fun upload(batch: UUID, case: UUID, input: WarehouseMigrationEvidenceInput, key: String, contentType: String, bytes: ByteArray): String {
        receiptKey(key)
        receiptText(input.label, 160)
        if (input.expectedEpoch !in 0 until Long.MAX_VALUE || input.label.any(Char::isISOControl) ||
            !input.expectedCaseHash.matches(Regex("[0-9a-f]{64}"))) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        validateReceiptEvidence(contentType, bytes)
        val cutover = cutovers.lockForCommand(input.expectedEpoch, WarehouseOperationClass.PROVENANCE_RESOLUTION)
        val current = access.current()
        store.lockBatch(batch, cutover.snapshot.epoch)
        access.sources(current)
        val sourceHash = store.caseHash(batch, case)
        if (sourceHash != input.expectedCaseHash) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val hash = digest(bytes)
        val payload = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("batchId" to batch, "caseId" to case,
            "input" to input, "sha256" to hash, "contentType" to contentType)))
        store.replay(key)?.let { prior ->
            if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.payloadHash != payload.hash || prior.view.batchId != batch || prior.view.caseId != case)
                masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            verified(prior)
            return prior.originalBody
        }
        val id = UUID.randomUUID()
        val tenant = current.fence.identity.tenantId
        val objectKey = "$tenant/warehouse/migrations/$batch/$case/$id"
        val view = WarehouseMigrationEvidence(id, batch, case, sourceHash, input.label, contentType, bytes.size.toLong(), hash,
            current.fence.identity.userId, clock.now())
        val stored = store.insert(view, objectKey, key, payload, cutover.snapshot.epoch)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) reconciliation.reconcile(tenant, stored)
            }
        })
        storage.put(objectKey, contentType, bytes)
        verified(stored)
        return stored.originalBody
    }

    fun list(batch: UUID, case: UUID, page: Int, size: Int): WarehousePage<WarehouseMigrationEvidence> {
        if (page < 0 || size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        read(batch, case)
        return store.list(batch, case, page, size)
    }

    fun download(batch: UUID, case: UUID, id: UUID): StoredObject {
        read(batch, case)
        return verified(store.get(batch, case, id))
    }

    internal fun verified(evidence: StoredMigrationEvidence): StoredObject {
        val objectValue = try { storage.get(evidence.objectKey) } catch (_: NotFoundException) { masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED) }
        if (objectValue.size != evidence.view.sizeBytes || objectValue.contentType != evidence.view.contentType || digest(objectValue.bytes) != evidence.view.sha256)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        validateReceiptEvidence(objectValue.contentType, objectValue.bytes)
        return objectValue
    }

    private fun read(batch: UUID, case: UUID) {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.MIGRATION_REPORT)
        access.sources(access.current())
        store.caseHash(batch, case)
    }
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
