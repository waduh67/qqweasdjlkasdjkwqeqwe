package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseOperationStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseTransferStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.MovementKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class], timeout = 30)
class WarehouseTransferService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val access: WarehouseTransferAccess, private val planning: WarehouseTransferPlanning,
    private val store: WarehouseTransferStore, private val operations: WarehouseOperationStore,
    private val posting: WarehousePosting) : InventoryTransferApi {
    private val mapper = jacksonObjectMapper()

    override fun create(request: WarehouseTransferDraft, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        receiptKey(metadata.idempotencyKey)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.transfer.manage")
        access.authorize(request, current)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(request))
        val prior = operations.lockKey("warehouse.transfer.create", metadata.idempotencyKey)
        if (prior != null) {
            authorizeReplay(prior, current, cutover, canonical, prior.resourceId)
            access.authorize(store.get(prior.resourceId), current)
            return prior.receipt
        }
        val id = UUID.randomUUID()
        val record = TransferRecord(id, "TR-$id", 0, WarehouseTransferState.DRAFT, request,
            current.fence.identity.userId, Instant.now(), planning.draft(request, current.fence.identity.userId))
        store.create(record, current.fence.epoch, cutover.snapshot.epoch)
        val operation = operation("create", metadata, canonical, record, current, 201)
        store.draftOperation(operation, cutover.snapshot.epoch)
        store.seal(record, operation, current.fence.identity.sessionId)
        return receipt(record, operation)
    }

    override fun dispatch(id: UUID, request: WarehouseTransferRevision, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt =
        execute(id, request.expectedRevision, request, metadata, "dispatch") { record, _ ->
            if (record.state != WarehouseTransferState.DRAFT) masterFailure(WarehouseErrorCode.STALE_REVISION)
            planning.dispatch(record)
        }

    override fun receive(id: UUID, request: WarehouseTransferReceipt, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt =
        execute(id, request.expectedRevision, request, metadata, "receive") { record, locations ->
            if (record.state !in setOf(WarehouseTransferState.DISPATCHED, WarehouseTransferState.PART_RECEIVED))
                masterFailure(WarehouseErrorCode.STALE_REVISION)
            planning.receive(record, request, locations.destination)
        }

    fun cancel(id: UUID, request: WarehouseTransferRevision): Nothing {
        val record = get(id)
        receiptPermission(authority.lockCurrent(), "inventory.transfer.manage")
        if (record.revision != request.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    override fun get(id: UUID): WarehouseTransferView {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.transfer.view")
        val record = store.get(id)
        access.authorize(record, current)
        return record.view()
    }

    override fun history(id: UUID): List<WarehouseTransferView> {
        get(id)
        return store.history(id)
    }

    private fun execute(id: UUID, revision: Long, input: Any, metadata: WarehouseMutationMetadata, action: String,
        prepare: (TransferRecord, TransferLocations) -> TransferPosting): WarehouseOperationReceipt {
        receiptKey(metadata.idempotencyKey)
        if (revision !in 0 until Long.MAX_VALUE) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.transfer.manage")
        val preview = store.get(id)
        val locations = access.authorize(preview, current)
        val actor = current.fence.identity.userId
        if (action == "receive" && actor != preview.binding.receiverId || action == "dispatch" && actor != preview.sender)
            masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
        val record = store.get(id, true)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to id, "request" to input)))
        val prior = operations.lockKey("warehouse.transfer.$action", metadata.idempotencyKey)
        if (prior != null) {
            authorizeReplay(prior, current, cutover, canonical, id)
            return prior.receipt
        }
        if (record.revision != revision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val prepared = prepare(record, locations)
        val state = if (action == "dispatch") WarehouseTransferState.DISPATCHED else
            if (prepared.lines.all { it.received == it.quantity }) WarehouseTransferState.RECEIVED else WarehouseTransferState.PART_RECEIVED
        val updated = record.copy(revision = revision + 1, state = state, lines = prepared.lines, recordedAt = Instant.now())
        val operation = operation(action, metadata, canonical, updated, current, 200)
        posting.post(WarehousePost(id, revision, state.name, operation, MovementKind.TRANSFER, record.binding.reason,
            prepared.legs, splits = prepared.splits, events = listOf(PostingEvent(UUID.randomUUID(),
                if (action == "dispatch") WarehouseEventKind.DISPATCHED else WarehouseEventKind.ACKNOWLEDGED, operation.originalBody))), cutover)
        store.seal(updated, operation, current.fence.identity.sessionId)
        return receipt(updated, operation)
    }

    private fun authorizeReplay(prior: com.duluin.ftth.inventory.adapter.outbound.persistence.StoredWarehouseOperation,
        current: CurrentAuthority, cutover: TenantCutoverFence, canonical: WarehouseCanonicalPayload, id: UUID) {
        if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        if (prior.hash != canonical.hash || prior.resourceId != id) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        if (prior.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
    }

    private fun operation(action: String, metadata: WarehouseMutationMetadata, canonical: WarehouseCanonicalPayload,
        record: TransferRecord, current: CurrentAuthority, status: Int): PostingOperation = PostingOperation(UUID.randomUUID(),
        "warehouse.transfer.$action", metadata.idempotencyKey, current.fence.identity.userId, record.id, "transfer:${record.id}",
        canonical.hash, action.uppercase(), status, mapper.writeValueAsString(record.view()), current.fence.epoch, record.recordedAt)

    private fun receipt(record: TransferRecord, operation: PostingOperation): WarehouseOperationReceipt = WarehouseOperationReceipt(
        operation.id, record.id, record.revision, operation.originalStatus, operation.originalBody, operation.recordedAt)
}
