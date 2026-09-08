package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseOperationStore
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID

@Component
class WarehouseLegacyCommandExecutor(
    private val cutovers: InventoryTenantCutoverApi,
    private val authority: CurrentAuthorityApi,
    private val scopes: InventoryWarehouseScopeApi,
    private val workOrders: InventoryCommandWorkOrderPort,
    private val operations: WarehouseOperationStore,
    private val legacy: LegacyWarehousePostingPort,
    private val posting: WarehousePosting,
) {
    private val mapper = jacksonObjectMapper()

    fun executeMovement(command: MovementCommand): InventoryMovement {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK).assertHeld()
        val current = authority.lockCurrent()
        if (command.tenantId != current.fence.identity.tenantId || command.actorId != current.fence.identity.userId) fail(WarehouseErrorCode.FORBIDDEN)
        val prior = operations.findKey("warehouse.legacy.consume", command.operationKey)
        val request = if (prior == null) legacy.fulfillment(command) else {
            if (prior.actorId != current.fence.identity.userId) fail(WarehouseErrorCode.FORBIDDEN)
            val stored = mapper.readTree(operations.identity(prior.receipt.operationId)).path("request")
            val leg = command.legs.single()
            require(command.kind == MovementKind.CONSUME && leg.direction == LegDirection.OUT)
            InventoryFulfillmentCommand(command.tenantId, UUID.fromString(stored.path("targetId").asString()), leg.itemId,
                leg.skuId, leg.locationId, UUID.fromString(stored.path("customerId").asString()), UUID.fromString(stored.path("workOrderId").asString()),
                leg.quantity, leg.serialized, true, command.actorId, "warehouse.legacy.consume", command.operationKey, "", command.reason,
                stored.path("itemCategory").asString())
        }
        return execute(request, false)
    }

    fun execute(request: InventoryFulfillmentCommand, returned: Boolean): InventoryMovement {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        val identity = current.fence.identity
        if (request.tenantId != identity.tenantId || request.actorId != identity.userId) fail(WarehouseErrorCode.FORBIDDEN)
        if (request.operationKey.length !in 1..240 || request.operationKey.any { it.code !in 33..126 }) fail(WarehouseErrorCode.MALFORMED_REQUEST)
        val permission = if (returned) "inventory.return.manage" else "workorder.order.field"
        if (!current.platformAdmin && permission !in current.permissions) fail(WarehouseErrorCode.FORBIDDEN)
        val namespace = if (returned) "warehouse.legacy.return" else "warehouse.legacy.consume"
        val command = request.copy(tenantId = identity.tenantId, actorId = identity.userId, namespace = namespace, payloadHash = "")
        val requestJson = mapper.valueToTree<tools.jackson.databind.node.ObjectNode>(command)
        requestJson.remove("payloadHash"); requestJson.remove("namespace"); requestJson.remove("operationKey")
        val workOrderRevision = workOrders.lock(command.workOrderId, null, current, command.customerId, !returned)
        val warehouseScope = scopes.currentUnderFence(current.fence)
        fun authorize(locations: Set<UUID>) {
            if (!current.platformAdmin) {
                if (warehouseScope is AuthorityScope.Restricted && !warehouseScope.ids.containsAll(locations)) fail(WarehouseErrorCode.FORBIDDEN)
                val areaScope = current.areaScope
                if (areaScope is AuthorityScope.Restricted && operations.locationAreas(locations).values.any { it != null && it !in areaScope.ids }) fail(WarehouseErrorCode.FORBIDDEN)
            }
        }
        val preview = operations.findKey(namespace, command.operationKey)
        if (preview != null && (preview.actorId != identity.userId || preview.resourceId != command.workOrderId)) fail(WarehouseErrorCode.FORBIDDEN)
        val source = if (preview == null) legacy.reference(command) else null
        val snapshot = if (preview != null) references(preview.receipt.operationId) else {
            val issued = requireNotNull(source)
            LegacyReferences(mapOf("document:${issued.document}" to issued.revision, "workorder:${command.workOrderId}" to workOrderRevision),
                setOf(command.locationId, issued.returnLocation))
        }
        authorize(snapshot.locations)
        if (snapshot.revisions["workorder:${command.workOrderId}"] != workOrderRevision) fail(WarehouseErrorCode.STALE_REVISION)
        operations.lockDocuments(null, snapshot.revisions)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("request" to requestJson,
            "references" to snapshot.revisions, "locations" to snapshot.locations.sortedBy(UUID::toString))))
        val prior = operations.lockKey(namespace, command.operationKey)
        if (prior != null) {
            if (prior.actorId != identity.userId || prior.resourceId != command.workOrderId) fail(WarehouseErrorCode.FORBIDDEN)
            if (references(prior.receipt.operationId) != snapshot || prior.hash != canonical.hash || prior.scope != scope(snapshot.locations)) {
                fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            }
            if (prior.cutoverEpoch != cutover.snapshot.epoch) fail(WarehouseErrorCode.STALE_CUTOVER)
            return mapper.readValue(prior.receipt.originalBody)
        }
        if (preview != null) fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        val post = legacy.resolve(command, returned, cutover.snapshot.epoch, requireNotNull(source))
        val operation = post.operation.copy(actorId = identity.userId, resourceId = command.workOrderId,
            resourceScope = scope(snapshot.locations), payloadHash = canonical.hash, authorityEpoch = current.fence.epoch)
        val result = InventoryMovement(operation.postingId, identity.tenantId, namespace, command.operationKey, canonical.hash, identity.userId,
            command.reason, operation.recordedAt, post.kind, post.legs.map { leg ->
                MovementLeg(leg.direction, leg.dimension.stockIdentityId, leg.dimension.skuId, leg.dimension.locationId,
                    Math.toIntExact(leg.quantity.quantityBase), true, leg.dimension.custodianId, leg.dimension.custodianKind, leg.status)
            }, MovementState.APPLIED)
        posting.post(post.copy(operation = operation.copy(originalBody = mapper.writeValueAsString(result))), cutover)
        operations.storeIdentity(operation.id, canonical.json, identity.sessionId)
        return result
    }

    private data class LegacyReferences(val revisions: Map<String, Long>, val locations: Set<UUID>)

    private fun references(operationId: UUID): LegacyReferences {
        val stored = mapper.readTree(operations.identity(operationId))
        return LegacyReferences(stored.path("references").properties().associate { it.key to it.value.asLong() },
            stored.path("locations").asSequence().map { UUID.fromString(it.asString()) }.toSet())
    }

    private fun scope(locations: Set<UUID>): String = locations.sortedBy(UUID::toString).joinToString(",", "locations:")

    private fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code, code.name))
}
