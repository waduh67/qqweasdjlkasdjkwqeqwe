package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.PostingOperation
import com.duluin.ftth.inventory.domain.model.LocationKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

@Service
class WarehouseTransferDiscrepancyService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val access: WarehouseTransferAccess, private val locations: WarehouseReceiptService, private val scopes: InventoryWarehouseScopeApi,
    private val store: WarehouseTransferStore, private val resolutions: WarehouseTransferDiscrepancyStore,
    private val operations: WarehouseOperationStore) {
    private val mapper = jacksonObjectMapper()

    @Transactional(rollbackFor = [Exception::class], timeout = 30)
    fun report(id: UUID, request: WarehouseTransferDiscrepancy, key: String): WarehouseOperationReceipt {
        receiptKey(key)
        if (request.expectedRevision !in 1 until Long.MAX_VALUE || request.reason.isBlank() || request.reason.length > 1000 ||
            request.evidenceReference.isBlank() || request.evidenceReference.length > 500) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.transfer.manage")
        receiptPermission(current, "inventory.approval.request")
        val preview = store.get(id)
        access.authorize(preview, current)
        val actor = current.fence.identity.userId
        if (actor != preview.binding.receiverId) masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
        val target = locations.authorizeLocation(request.destinationLocationId, current, scopes.currentUnderFence(current.fence))
        if (target.issueEligible || target.kind != (if (request.action == TransferRemainderAction.LOST) LocationKind.LOST else LocationKind.QUARANTINE))
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val record = store.get(id, true)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to id, "request" to request)))
        val prior = operations.lockKey("warehouse.transfer.discrepancy", key)
        if (prior != null) {
            if (prior.actorId != actor) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.resourceId != id || prior.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            return prior.receipt
        }
        if (record.revision != request.expectedRevision || record.state !in setOf(WarehouseTransferState.DISPATCHED, WarehouseTransferState.PART_RECEIVED))
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        val updated = record.copy(revision = record.revision + 1, state = WarehouseTransferState.DISCREPANCY,
            resolutionDocumentId = UUID.randomUUID(), recordedAt = Instant.now())
        val operation = PostingOperation(UUID.randomUUID(), "warehouse.transfer.discrepancy", key, actor, id, "transfer:$id",
            canonical.hash, "DISCREPANCY", 200, mapper.writeValueAsString(updated.view()), current.fence.epoch, updated.recordedAt)
        store.advanceWithoutPosting(updated, operation, cutover.snapshot.epoch, current.fence.identity.sessionId)
        resolutions.create(updated, request, actor, current.fence.epoch, cutover.snapshot.epoch)
        return WarehouseOperationReceipt(operation.id, id, updated.revision, 200, operation.originalBody, operation.recordedAt)
    }
}
