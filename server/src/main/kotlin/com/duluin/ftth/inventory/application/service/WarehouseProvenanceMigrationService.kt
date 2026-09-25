package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseProvenanceStore
import com.duluin.ftth.inventory.application.port.inbound.WarehouseMigrationBeginInput
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

/** A tenant migration needs authority over every affected source, checked before counts or rows. */
@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class WarehouseProvenanceMigrationService(private val cutovers: InventoryTenantCutoverApi,
    private val policy: InventoryTenantPolicyService,
    private val access: WarehouseProvenanceAccess, private val store: WarehouseProvenanceStore) {

    private val mapper = jacksonObjectMapper()

    fun begin(input: WarehouseMigrationBeginInput, key: String): String {
        receiptKey(key)
        if (input.expectedEpoch !in 0 until Long.MAX_VALUE || !input.expectedPreservationHash.matches(Regex("[0-9a-f]{64}"))) malformed()
        // Read the current fence first so a response-loss replay can cross its own LEGACY -> VALIDATING transition.
        val fence = cutovers.lockForTransition(cutovers.read().epoch)
        val current = access.current()
        access.sources(current)
        val payload = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(input))
        store.replay(key)?.let { prior ->
            if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.hash != payload.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.cutoverEpoch != fence.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            return prior.body
        }
        if (input.expectedEpoch != fence.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        if (mapper.readTree(store.summary(fence.snapshot)).path("preservationHash").asString() != input.expectedPreservationHash)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        val validating = when (fence.snapshot.state) {
            WarehouseCutoverState.LEGACY -> policy.beginValidation(input.expectedEpoch)
            WarehouseCutoverState.VALIDATING -> fence.snapshot
            WarehouseCutoverState.ENFORCED -> masterFailure(WarehouseErrorCode.CUTOVER_REQUIRED)
        }
        store.capture(validating, current.fence.identity.userId)
        val body = store.summary(validating)
        store.record(key, payload, input.expectedEpoch, validating, current.fence.identity.userId, current.fence.epoch, body)
        return body
    }

    fun summary(): String {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.MIGRATION_REPORT)
        access.sources(access.current())
        return store.summary(cutover.snapshot)
    }

    fun cases(page: Int, size: Int, sourceTable: String?, id: UUID? = null): String {
        if (page < 0 || size !in 1..100 || (sourceTable != null && sourceTable !in sourceTables)) malformed()
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.MIGRATION_REPORT)
        val references = access.sources(access.current())
        return store.cases(page, size, sourceTable, id, references)
    }

    private fun malformed(): Nothing = masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    companion object {
        val sourceTables = setOf("inventory_serialized_asset", "inventory_balance_projection", "onu", "inventory_serial_tombstone",
            "inventory_movement", "inventory_movement_leg", "inventory_fulfillment_effect", "inventory_customer_material_fact")
    }
}
