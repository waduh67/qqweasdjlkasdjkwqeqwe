package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.MigrationEvidenceStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MigrationResolutionStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehousePolicyPersistence
import com.duluin.ftth.inventory.application.port.inbound.WarehouseMigrationResolutionInput
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class MigrationResolutionService(private val cutovers: InventoryTenantCutoverApi, private val access: WarehouseProvenanceAccess,
    private val batches: MigrationEvidenceStore, private val files: MigrationEvidenceService,
    private val store: MigrationResolutionStore, private val clock: WarehousePolicyPersistence) {
    private val mapper = jacksonObjectMapper()

    fun resolve(batch: UUID, case: UUID, input: WarehouseMigrationResolutionInput, key: String): String {
        receiptKey(key)
        receiptText(input.reason, 2000)
        if (input.expectedEpoch !in 0 until Long.MAX_VALUE || input.expectedResolutionRevision !in 0 until Long.MAX_VALUE ||
            !input.expectedCaseHash.matches(Regex("[0-9a-f]{64}")) || input.reason.any(Char::isISOControl) ||
            input.evidenceIds.size !in 1..10 || input.evidenceIds.distinct().size != input.evidenceIds.size ||
            (input.kind == MigrationResolutionKind.BASELINE_STOCK) != (input.stock != null) ||
            (input.kind == MigrationResolutionKind.DUPLICATE) != (input.duplicateCaseId != null) || input.duplicateCaseId == case)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val fence = cutovers.lockForCommand(input.expectedEpoch, WarehouseOperationClass.PROVENANCE_RESOLUTION)
        val current = access.current()
        batches.lockBatch(batch, fence.snapshot.epoch)
        access.sources(current)
        if (batches.caseHash(batch, case) != input.expectedCaseHash) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val payload = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("batchId" to batch, "caseId" to case, "input" to input)))
        store.replay(key)?.let { prior ->
            if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.payloadHash != payload.hash || prior.view.batchId != batch || prior.view.caseId != case)
                masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.epoch != fence.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            verifyEvidence(batch, case, input.evidenceIds)
            return prior.body
        }
        if ((store.latest(batch, case)?.view?.revision ?: 0) != input.expectedResolutionRevision)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        if ((input.kind == MigrationResolutionKind.CANCEL_PENDING) != store.pending(case))
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val stock = input.stock?.let { store.stock(case, it) }
        val winner = input.duplicateCaseId?.let { target ->
            batches.caseHash(batch, target)
            store.duplicate(batch, case, target)
        }
        val evidence = verifyEvidence(batch, case, input.evidenceIds)
        val view = WarehouseMigrationResolution(UUID.randomUUID(), batch, case, input.expectedCaseHash,
            input.expectedResolutionRevision + 1, input.kind, input.reason, evidence, stock, input.duplicateCaseId, winner,
            current.fence.identity.userId, clock.now())
        return store.insert(view, key, payload, fence.snapshot.epoch)
    }

    fun history(batch: UUID, case: UUID, page: Int, size: Int): WarehousePage<WarehouseMigrationResolution> {
        if (page < 0 || size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.MIGRATION_REPORT)
        access.sources(access.current())
        batches.caseHash(batch, case)
        return store.history(batch, case, page, size)
    }

    private fun verifyEvidence(batch: UUID, case: UUID, ids: List<UUID>): List<MigrationEvidenceReference> = ids.map { id ->
        val evidence = batches.get(batch, case, id)
        files.verified(evidence)
        MigrationEvidenceReference(id, evidence.view.sourceHash, evidence.view.sha256, evidence.view.uploadedBy)
    }
}
