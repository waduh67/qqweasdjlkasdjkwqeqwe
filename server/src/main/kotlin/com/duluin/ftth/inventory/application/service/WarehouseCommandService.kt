package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseOperationStore
import com.duluin.ftth.inventory.application.port.outbound.PostingOperation
import com.duluin.ftth.inventory.application.port.outbound.WarehousePosting
import com.duluin.ftth.inventory.domain.model.MovementKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
class WarehouseCommandService(
    private val cutovers: InventoryTenantCutoverApi,
    private val authority: CurrentAuthorityApi,
    private val scopes: InventoryWarehouseScopeApi,
    private val operations: WarehouseOperationStore,
    private val posting: WarehousePosting,
    private val workOrders: InventoryCommandWorkOrderPort,
    private val legacy: WarehouseLegacyCommandExecutor,
    private val masters: WarehouseMasterService,
    private val receipts: WarehouseReceiptService,
) {
    @Transactional(rollbackFor = [Exception::class])
    fun draftReceipt(id: UUID?, input: com.duluin.ftth.inventory.application.port.inbound.ReceiptDraftInput, key: String): WarehouseOperationReceipt =
        receipts.draft(id, input, key)

    @Transactional(rollbackFor = [Exception::class])
    fun executeMaster(kind: com.duluin.ftth.inventory.application.port.inbound.MasterKind,
        action: com.duluin.ftth.inventory.application.port.inbound.MasterAction, id: UUID?,
        input: com.duluin.ftth.inventory.application.port.inbound.MasterInput, key: String): WarehouseOperationReceipt =
        masters.execute(kind, action, id, input, key)

    @Transactional(rollbackFor = [Exception::class])
    fun executeLegacy(command: InventoryFulfillmentCommand, returned: Boolean): com.duluin.ftth.inventory.domain.model.InventoryMovement =
        legacy.execute(command, returned)

    @Transactional(rollbackFor = [Exception::class])
    fun executeMovement(command: com.duluin.ftth.inventory.domain.model.MovementCommand): com.duluin.ftth.inventory.domain.model.InventoryMovement =
        legacy.executeMovement(command)

    @Transactional(rollbackFor = [Exception::class])
    fun execute(command: WarehousePreparedCommand): WarehouseOperationReceipt {
        val cutover = cutovers.lockForCommand(command.cutoverEpoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        val warehouseScope = scopes.currentUnderFence(current.fence)
        val permission = when (command.posting.kind) {
            MovementKind.RECEIVE -> "inventory.receipt.manage"
            MovementKind.RESERVE, MovementKind.RELEASE -> "inventory.request.manage"
            MovementKind.TRANSFER -> "inventory.transfer.manage"
            MovementKind.RETURN -> "inventory.return.manage"
            else -> fail(WarehouseErrorCode.FORBIDDEN)
        }
        if (!current.platformAdmin) {
            if (permission !in current.permissions) fail(WarehouseErrorCode.FORBIDDEN)
            if (warehouseScope is AuthorityScope.Restricted && !warehouseScope.ids.containsAll(command.locations)) fail(WarehouseErrorCode.FORBIDDEN)
            val areaScope = current.areaScope
            if (areaScope is AuthorityScope.Restricted && operations.locationAreas(command.locations).values.any { it != null && it !in areaScope.ids }) fail(WarehouseErrorCode.FORBIDDEN)
        }
        command.referencedRevisions.filterKeys { it.startsWith("workorder:") }.toSortedMap().forEach { (reference, revision) ->
            workOrders.lock(UUID.fromString(reference.substringAfter(':')), revision, current, null, false)
        }
        operations.lockDocuments(command.documentId, command.referencedRevisions)
        val prior = operations.lock(command)
        if (prior != null) {
            if (prior.actorId != current.fence.identity.userId) fail(WarehouseErrorCode.FORBIDDEN)
            if (prior.hash != command.canonical.hash || prior.resourceId != command.documentId || prior.scope != command.resourceScope) fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.cutoverEpoch != cutover.snapshot.epoch) fail(WarehouseErrorCode.STALE_CUTOVER)
            return prior.receipt
        }
        if (command.authorityEpoch != null && command.authorityEpoch != current.fence.epoch) fail(WarehouseErrorCode.STALE_AUTHORITY)
        if (operations.hasBusinessAction(command)) fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        val id = UUID.randomUUID()
        val body = jacksonObjectMapper().writeValueAsString(mapOf("operationId" to id, "documentId" to command.documentId, "documentRevision" to command.revision))
        val operation = PostingOperation(id, command.namespace, command.key, current.fence.identity.userId,
            command.documentId, command.resourceScope, command.canonical.hash, command.posting.kind.name, 200, body, current.fence.epoch)
        posting.post(command.posting.copy(operation = operation), cutover)
        operations.storeIdentity(id, command, current.fence.identity.sessionId)
        return requireNotNull(operations.lock(command)).receipt
    }

    private fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code, code.name))
}
