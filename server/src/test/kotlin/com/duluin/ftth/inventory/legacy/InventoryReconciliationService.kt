package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.domain.model.*
import java.time.Clock
import java.time.Instant
import java.util.UUID

class InventoryReconciliationService(
    private val ledger: InventoryMovementLedgerService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val monitor = Any()
    private val counts = linkedMapOf<UUID, CycleCount>()

    fun createCount(command: CycleCountCommand): CycleCount = synchronized(monitor) {
        require(command.tenantId == command.evidenceTenantId) { "evidence must belong to the same tenant" }
        val prior = ledger.rebuild(command.tenantId)
            .filter { it.skuId == command.skuId && it.locationId == command.locationId }
            .sumOf { it.quantity }
        val count = CycleCount(UUID.randomUUID(), command.tenantId, command.locationId, command.itemId, command.skuId, prior, command.observedQuantity, command.reason, command.evidenceReference, command.operationKey, command.operationHash, command.custodianId, Instant.now(clock), if (prior == command.observedQuantity) DiscrepancyState.RESOLVED else DiscrepancyState.OPEN)
        counts[count.countId] = count
        count
    }

    fun approveVariance(countId: UUID, approverId: UUID, operationKey: String, payloadHash: String): CycleCount = synchronized(monitor) {
        val count = counts[countId] ?: error("cycle count does not exist")
        require(count.discrepancy == DiscrepancyState.OPEN || count.discrepancy == DiscrepancyState.REWORK_REQUIRED)
        require(approverId != count.custodianId) { "custodian cannot approve own variance" }
        val delta = count.observedQuantity - count.priorQuantity
        if (delta != 0) {
            val direction = if (delta > 0) LegDirection.IN else LegDirection.OUT
            val correction = ledger.apply(MovementCommand(count.tenantId, approverId, "inventory.count.${count.countId}", operationKey, payloadHash, count.reason, MovementKind.COUNT_VARIANCE, listOf(MovementLeg(direction, count.itemId, count.skuId, count.locationId, kotlin.math.abs(delta), false, count.custodianId, OwnerKind.WAREHOUSE, InventoryStatus.AVAILABLE))))
            ledger.approvePending(correction.movementId)
        }
        val approved = count.copy(discrepancy = DiscrepancyState.RESOLVED, approverId = approverId, closedAt = Instant.now(clock))
        counts[countId] = approved
        approved
    }

    fun get(countId: UUID): CycleCount? = synchronized(monitor) { counts[countId] }

    fun discrepancies(tenantId: UUID): List<ReconciliationDiscrepancy> {
        val balances = ledger.rebuild(tenantId)
        return balances.groupBy { it.skuId }.map { (sku, entries) ->
            val serialized = entries.filter { it.quantity == 1 }.sumOf { it.quantity }
            val loose = entries.filter { it.quantity != 1 }.sumOf { it.quantity }
            val projected = entries.sumOf { it.quantity }
            ReconciliationDiscrepancy(tenantId, sku, serialized, loose, projected, projected < 0, serialized + loose != projected)
        }
    }
}

data class CycleCountCommand(
    val tenantId: UUID,
    val evidenceTenantId: UUID,
    val locationId: UUID,
    val itemId: UUID,
    val skuId: UUID,
    val observedQuantity: Int,
    val custodianId: UUID,
    val reason: String,
    val evidenceReference: String,
    val operationKey: String,
    val operationHash: String,
)
