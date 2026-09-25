package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.OpeningBalanceInput
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.inbound.WarehouseMigrationOpeningInput
import com.duluin.ftth.inventory.application.port.inbound.LocationSnapshot
import com.duluin.ftth.inventory.application.port.inbound.MasterKind
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MigrationOpeningStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MigrationEvidenceStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehousePolicyPersistence
import com.duluin.ftth.inventory.domain.model.LocationKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import tools.jackson.module.kotlin.jacksonObjectMapper

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class WarehouseOpeningBalanceService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val access: WarehouseProvenanceAccess, private val batches: MigrationEvidenceStore, private val files: MigrationEvidenceService,
    private val store: MigrationOpeningStore, private val masters: WarehouseMasterStore, private val masterAccess: WarehouseMasterService,
    private val scopes: InventoryWarehouseScopeApi, private val clock: WarehousePolicyPersistence) {
    private val mapper = jacksonObjectMapper()

    fun review(batch: UUID): WarehouseMigrationReview {
        val fence = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.MIGRATION_REPORT)
        val current = access.current()
        batches.lockBatch(batch, fence.snapshot.epoch)
        access.sources(current)
        return store.review(batch)
    }

    fun request(batch: UUID, input: WarehouseMigrationOpeningInput, key: String): String {
        receiptKey(key)
        receiptText(input.migrationReference, 500)
        receiptText(input.reason, 1000)
        if (input.expectedEpoch !in 0 until Long.MAX_VALUE || input.expectedReviewLocationRevision !in 0 until Long.MAX_VALUE ||
            !input.expectedReviewHash.matches(Regex("[0-9a-f]{64}")) || input.reason.any(Char::isISOControl) ||
            input.migrationReference.any(Char::isISOControl)) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val fence = cutovers.lockForCommand(input.expectedEpoch, WarehouseOperationClass.PROVENANCE_RESOLUTION)
        val current = access.current()
        batches.lockBatch(batch, fence.snapshot.epoch)
        val sourceAccess = access.sources(current)
        val location = masters.get(MasterKind.LOCATION, input.reviewLocationId) as LocationSnapshot
        masterAccess.authorizeLocation(location, current, scopes.currentUnderFence(current.fence))
        val payload = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("batchId" to batch, "input" to input)))
        store.replay(key)?.let { prior ->
            if (prior.view.requestedBy != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.payloadHash != payload.hash || prior.view.batchId != batch) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.view.cutoverEpoch != fence.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            verifyEvidence(prior.view.manifest)
            return prior.body
        }
        if (location.revision != input.expectedReviewLocationRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (location.state != WarehouseMasterState.ACTIVE || location.kind !in setOf(LocationKind.WAREHOUSE, LocationKind.BIN))
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val review = store.review(batch)
        if (review.reviewHash != input.expectedReviewHash) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (review.issues.isNotEmpty()) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        verifyEvidence(review.manifest)
        val id = UUID.randomUUID()
        val view = WarehouseMigrationOpening(id, "OPEN-$id", batch, review.reviewHash, review.manifest,
            MigrationReviewLocation(location.id, location.revision, location.areaId),
            MigrationOpeningScope(sourceAccess.customers.values.sortedBy { it.id }, sourceAccess.workOrders.values.sortedBy { it.id }),
            current.fence.identity.userId, current.fence.epoch, fence.snapshot.epoch, input.migrationReference, input.reason, clock.now())
        return store.insert(view, key, payload)
    }

    fun get(batch: UUID, id: UUID): String {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.MIGRATION_REPORT)
        val current = access.current()
        access.sources(current)
        val record = store.get(batch, id)
        val location = masters.get(MasterKind.LOCATION, record.view.reviewLocation.id) as LocationSnapshot
        masterAccess.authorizeLocation(location, current, scopes.currentUnderFence(current.fence))
        return record.body
    }

    internal fun verifyEvidence(manifest: MigrationReviewManifest) {
        manifest.cases.forEach { source -> source.resolution?.evidence?.forEach { reference ->
            val evidence = batches.get(manifest.batchId, source.caseId, reference.id)
            if (evidence.view.sourceHash != source.sourceHash || evidence.view.sha256 != reference.sha256 ||
                evidence.view.uploadedBy != reference.uploadedBy) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            files.verified(evidence)
        } }
    }

    @Transactional(rollbackFor = [Exception::class])
    fun request(input: OpeningBalanceInput, key: String, contentType: String, bytes: ByteArray): Nothing {
        receiptKey(key)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.provenance.manage")
        receiptText(input.migrationReference, 500)
        receiptText(input.sourceSnapshot, 10000)
        val cutoff = try { Instant.parse(input.cutoff) } catch (_: java.time.format.DateTimeParseException) { masterFailure(WarehouseErrorCode.MALFORMED_REQUEST) }
        if (cutoff > Instant.now()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        validateReceiptEvidence(contentType, bytes)
        masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
    }
}
