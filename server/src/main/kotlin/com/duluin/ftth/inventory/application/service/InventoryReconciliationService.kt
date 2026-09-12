package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.inventory.application.port.outbound.InventoryReconciliationRepository
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Stock opname (cycle count) dan penyelesaian selisihnya.
 *
 * Dulu hasil hitungan disimpan di `linkedMapOf` dalam memori: opname yang menemukan selisih
 * hilang sebelum sempat disetujui siapa pun, jadi kontrol yang seharusnya menangkap kebocoran
 * aset justru tidak meninggalkan bukti apa pun. Sekarang tersimpan di `inventory_cycle_count`.
 */
@Service
class InventoryReconciliationService(
    private val ledger: InventoryMovementLedgerService,
    private val counts: InventoryReconciliationRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun createCount(command: CycleCountCommand): CycleCount {
        require(command.tenantId == command.evidenceTenantId) { "evidence must belong to the same tenant" }
        counts.findByOperation(command.tenantId, command.operationKey)?.let { prior ->
            if (prior.operationHash != command.operationHash) throw ConflictException("cycle count operation key was used with a different payload")
            return prior
        }
        val prior = ledger.balances(command.tenantId)
            .filter { it.skuId == command.skuId && it.locationId == command.locationId }
            .sumOf { it.quantity }
        return counts.save(
            CycleCount(
                UuidV7.generate(), command.tenantId, command.locationId, command.itemId, command.skuId, prior,
                command.observedQuantity, command.reason, command.evidenceReference, command.operationKey,
                command.operationHash, command.custodianId, Instant.now(clock),
                if (prior == command.observedQuantity) DiscrepancyState.RESOLVED else DiscrepancyState.OPEN,
            ),
        )
    }

    @Transactional
    fun approveVariance(countId: UUID, approverId: UUID, operationKey: String, payloadHash: String): CycleCount {
        val count = counts.findById(countId) ?: throw NotFoundException("Stock opname tidak ditemukan")
        require(count.discrepancy == DiscrepancyState.OPEN || count.discrepancy == DiscrepancyState.REWORK_REQUIRED)
        // Empat mata: yang menghitung tidak boleh mengesahkan selisihnya sendiri, kalau tidak
        // barang yang hilang bisa "diputihkan" oleh orang yang sama yang memegangnya.
        require(approverId != count.custodianId) { "custodian cannot approve own variance" }
        val delta = count.observedQuantity - count.priorQuantity
        if (delta != 0) {
            val direction = if (delta > 0) LegDirection.IN else LegDirection.OUT
            val correction = ledger.apply(
                MovementCommand(
                    count.tenantId, approverId, "inventory.count.${count.countId}", operationKey, payloadHash,
                    count.reason, MovementKind.COUNT_VARIANCE,
                    listOf(MovementLeg(direction, count.itemId, count.skuId, count.locationId, kotlin.math.abs(delta), false, count.custodianId, OwnerKind.WAREHOUSE, InventoryStatus.AVAILABLE)),
                ),
            )
            // COUNT_VARIANCE lahir PENDING_APPROVAL; persetujuan inilah yang membuatnya berlaku,
            // jadi koreksinya langsung disahkan di sini agar saldo ikut bergerak.
            if (correction.state == MovementState.PENDING_APPROVAL) ledger.approvePending(correction.movementId)
        }
        return counts.save(count.copy(discrepancy = DiscrepancyState.RESOLVED, approverId = approverId, closedAt = Instant.now(clock)))
    }

    @Transactional(readOnly = true)
    fun get(countId: UUID): CycleCount? = counts.findById(countId)

    @Transactional(readOnly = true)
    fun open(tenantId: UUID): List<CycleCount> = counts.findOpen(tenantId)

    @Transactional(readOnly = true)
    fun discrepancies(tenantId: UUID): List<ReconciliationDiscrepancy> =
        ledger.balances(tenantId).groupBy { it.skuId }.map { (sku, entries) ->
            val serialized = entries.filter { it.quantity == 1 }.sumOf { it.quantity }
            val loose = entries.filter { it.quantity != 1 }.sumOf { it.quantity }
            val projected = entries.sumOf { it.quantity }
            ReconciliationDiscrepancy(tenantId, sku, serialized, loose, projected, projected < 0, serialized + loose != projected)
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
