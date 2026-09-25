package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.MigrationFinalizationStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MigrationOpeningStore
import com.duluin.ftth.inventory.application.port.inbound.WarehouseMigrationFinalizeInput
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class WarehouseMigrationFinalizationService(private val cutovers: InventoryTenantPolicyService,
    private val access: WarehouseProvenanceAccess, private val openings: MigrationOpeningStore,
    private val evidence: WarehouseOpeningBalanceService, private val store: MigrationFinalizationStore) {
    private val mapper = jacksonObjectMapper()

    fun review(batch: UUID): String {
        cutovers.lockCurrentForTransition()
        val current = access.current()
        openings.lockHistory(batch)
        access.sources(current)
        return store.review(batch)
    }

    fun finalize(batch: UUID, input: WarehouseMigrationFinalizeInput, key: String): String {
        receiptKey(key)
        receiptText(input.reason, 2000)
        if (input.expectedEpoch !in 1 until Long.MAX_VALUE || input.reason.any(Char::isISOControl) ||
            !input.expectedReviewHash.matches(Regex("[0-9a-f]{64}"))) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        // The exclusive fence serializes both finalizers and ordinary commands. A replay
        // checks current authority before crossing its own successful epoch transition.
        val cutover = cutovers.lockCurrentForTransition()
        val current = access.current()
        openings.lockHistory(batch)
        access.sources(current)
        val payload = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("batchId" to batch, "input" to input)))
        store.replay(key)?.let { prior ->
            if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.batchId != batch || prior.payloadHash != payload.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.resultingEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            return prior.body
        }
        if (input.expectedEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        if (cutover.snapshot.state != WarehouseCutoverState.VALIDATING) masterFailure(WarehouseErrorCode.CUTOVER_REQUIRED)
        val review = mapper.readTree(store.review(batch))
        if (!review.path("issues").isEmpty) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (review.path("openingDocumentId").asString() != input.openingDocumentId.toString() ||
            review.path("reviewHash").asString() != input.expectedReviewHash) masterFailure(WarehouseErrorCode.STALE_REVISION)
        evidence.verifyEvidence(openings.get(batch, input.openingDocumentId).view.manifest)
        return store.finalize(batch, current.fence.identity.userId, current.fence.epoch, key, payload)
    }
}
