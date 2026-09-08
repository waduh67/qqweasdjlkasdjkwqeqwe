package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseOperationStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseReceiptOrigins
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseReceiptPersistence
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReceiptInspectionPersistence
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.MovementKind
import org.springframework.stereotype.Service
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
class ReceiptTransitionService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val scopes: InventoryWarehouseScopeApi, private val masters: WarehouseMasterStore,
    private val receipts: WarehouseReceiptService, private val store: WarehouseReceiptPersistence,
    private val operations: WarehouseOperationStore, private val origins: WarehouseReceiptOrigins, private val posting: WarehousePosting,
    private val planning: ReceiptDispositionPlanning, private val inspections: ReceiptInspectionPersistence, private val completion: ReceiptCompletion) {
    private val mapper = jacksonObjectMapper()
    fun execute(id: UUID, input: ReceiptInput, key: String): WarehouseOperationReceipt {
        receiptKey(key)
        if (input.expectedRevision == null || input.expectedRevision !in 0 until Long.MAX_VALUE) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val action = when (input) {
            is ReceiptReceiveInput -> "RECEIVE"
            is ReceiptInspectInput -> "INSPECT"
            is ReceiptPutawayInput -> "PUTAWAY"
            is ReceiptDraftInput -> masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        }
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.receipt.manage")
        masters.lockTopology()
        val scope = scopes.currentUnderFence(current.fence)
        val record = store.get(id, true)
        receipts.authorize(record.intake, current, scope)
        val destination = if (input is ReceiptPutawayInput) receipts.authorizeLocation(input.destinationLocationId, current, scope) else null
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to id, "input" to input)))
        val namespace = "warehouse.receipt.${action.lowercase()}"
        val prior = operations.lockKey(namespace, key)
        if (prior != null) {
            if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.resourceId != id || prior.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            receipts.authorize(mapper.readValue(operations.identity(prior.receipt.operationId), ReceiptIntake::class.java), current, scope)
            return prior.receipt
        }
        if (record.revision != input.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (record.state != if (input is ReceiptReceiveInput) WarehouseReceiptState.DRAFT else WarehouseReceiptState.RECEIVED_IN_INSPECTION)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val plan = when (input) {
            is ReceiptReceiveInput -> ReceiptDispositionPlan(origins.admit(record), emptyList(), emptyList(), WarehouseReceiptState.RECEIVED_IN_INSPECTION)
            is ReceiptInspectInput -> planning.inspect(record, input)
            is ReceiptPutawayInput -> planning.putaway(record, input, requireNotNull(destination))
            is ReceiptDraftInput -> masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        }
        val revision = Math.addExact(record.revision, 1)
        val operationId = UUID.randomUUID()
        val nextState = if (input is ReceiptReceiveInput) plan.state else completion.state(record, plan)
        val body = mapper.writeValueAsString(mapOf("id" to id, "revision" to revision, "state" to nextState, "operationId" to operationId))
        val operation = PostingOperation(operationId, namespace, key, current.fence.identity.userId, id, "receipt:$id",
            canonical.hash, action, 200, body, current.fence.epoch)
        if (plan.legs.isEmpty()) {
            store.advance(id, record.revision, nextState)
            store.operation(operation, revision, cutover.snapshot.epoch)
        } else posting.post(WarehousePost(id, record.revision, nextState.name, operation,
            if (input is ReceiptReceiveInput) MovementKind.RECEIVE else MovementKind.TRANSFER,
            "$action ${record.intake.externalReference}", plan.legs, splits = plan.splits), cutover)
        inspections.save(plan.decisions, operationId, current.fence.identity.userId)
        if (input !is ReceiptReceiveInput) check(completion.state(record) == nextState) { "Receipt completion differs from durable disposition" }
        operations.storeIdentity(operationId, mapper.writeValueAsString(record.intake), current.fence.identity.sessionId)
        return WarehouseOperationReceipt(operationId, id, revision, 200, body, operation.recordedAt)
    }
}
