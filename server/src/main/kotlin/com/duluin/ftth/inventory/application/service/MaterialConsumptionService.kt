package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.application.port.outbound.MaterialConsumptionRepository
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Mencatat material apa yang benar-benar terpasang di sisi pelanggan, sekaligus memotong
 * stoknya di ledger dalam transaksi yang SAMA.
 *
 * Dulu fakta ini hidup di `linkedMapOf` dalam memori: panel "material terpasang" di
 * Subscriber 360 kosong setiap kali aplikasi restart, dan ketika pelanggan komplain tidak
 * ada catatan bahwa ONT-nya pernah dipasang sama sekali. Sekarang tersimpan di
 * `inventory_customer_material_fact`.
 */
@Service
class MaterialConsumptionService(
    private val ledger: InventoryMovementLedgerService,
    private val facts: MaterialConsumptionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun consume(command: MaterialConsumptionCommand): CustomerMaterialFact = record(command, returned = false) {
        require(command.installed) { "consumption must describe installed material" }
        ledger.apply(command.toMovement(MovementKind.CONSUME, InventoryStatus.ISSUED, LegDirection.OUT))
    }

    @Transactional
    fun returnUnused(command: MaterialConsumptionCommand): CustomerMaterialFact = record(command, returned = true) {
        require(!command.installed) { "returned material cannot be installed" }
        ledger.apply(command.toMovement(MovementKind.RETURN, InventoryStatus.RETURNED, LegDirection.IN))
    }

    @Transactional(readOnly = true)
    fun forCustomer(tenantId: UUID, customerId: UUID): List<CustomerMaterialFact> = facts.forCustomer(tenantId, customerId)

    @Transactional(readOnly = true)
    fun hasRecorded(command: MaterialConsumptionCommand): Boolean =
        facts.findByOperation(command.tenantId, command.operationKey)?.payloadHash == command.payloadHash

    private fun record(command: MaterialConsumptionCommand, returned: Boolean, movement: () -> Unit): CustomerMaterialFact {
        facts.findByOperation(command.tenantId, command.operationKey)?.let { return replayOrConflict(it, command) }

        // Mutasi ledger dijalankan LEBIH DULU: kalau stoknya kurang, seluruh transaksi gagal
        // dan fakta material tidak pernah lahir. Urutan sebaliknya akan menyisakan catatan
        // "terpasang di pelanggan" untuk barang yang tak pernah keluar gudang.
        movement()
        val fact = CustomerMaterialFact(
            command.tenantId, command.customerId, command.workOrderId, command.itemCategory,
            command.quantity, command.installed, returned, Instant.now(clock),
        )
        val raced = facts.appendIfAbsent(fact, command.operationKey, command.payloadHash) ?: return fact
        return replayOrConflict(raced, command)
    }

    private fun replayOrConflict(stored: com.duluin.ftth.inventory.application.port.outbound.RecordedMaterialFact, command: MaterialConsumptionCommand): CustomerMaterialFact {
        require(stored.payloadHash == command.payloadHash) { "operation key was used with a different payload" }
        require(stored.fact.customerId == command.customerId && stored.fact.workOrderId == command.workOrderId) { "operation key is bound to another work order" }
        return stored.fact
    }

    private fun MaterialConsumptionCommand.toMovement(kind: MovementKind, status: InventoryStatus, direction: LegDirection) = MovementCommand(
        tenantId, actorId, "workorder.material.$workOrderId", operationKey, payloadHash, reason, kind,
        listOf(MovementLeg(direction, itemId, skuId, locationId, quantity, serialized, actorId, OwnerKind.TECHNICIAN, status, assetId, serialNumber)),
    )
}
