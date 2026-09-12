package com.duluin.ftth.inventory

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.inventory.application.port.outbound.InventoryApprovalAuditRepository
import com.duluin.ftth.inventory.application.port.outbound.InventoryApprovalPolicyRepository
import com.duluin.ftth.inventory.application.port.outbound.InventoryApprovalRepository
import com.duluin.ftth.inventory.application.port.outbound.InventoryLedgerRepository
import com.duluin.ftth.inventory.application.port.outbound.InventoryReconciliationRepository
import com.duluin.ftth.inventory.application.port.outbound.MaterialConsumptionRepository
import com.duluin.ftth.inventory.application.port.outbound.MovementFilter
import com.duluin.ftth.inventory.application.port.outbound.RecordedMaterialFact
import com.duluin.ftth.inventory.domain.model.*
import java.time.Instant
import java.util.UUID

/**
 * Ganda in-memory untuk port outbound modul inventory.
 *
 * Ada supaya aturan domain (idempotency, saldo tidak boleh minus, empat mata) tetap bisa
 * diuji cepat tanpa Postgres. Perilakunya SENGAJA meniru jaminan basis data yang sebenarnya —
 * terutama "sisip kalau belum ada, kalau bentrok kembalikan yang sudah ada" — karena ganda
 * yang lebih longgar dari aslinya akan meloloskan bug idempotency yang justru mau dicegah.
 */
class FakeInventoryLedger : InventoryLedgerRepository {
    private val movements = linkedMapOf<UUID, InventoryMovement>()
    private val balances = linkedMapOf<BalanceKey, Int>()
    private val skuOfKey = mutableMapOf<BalanceKey, UUID>()

    @Synchronized
    override fun findByOperation(tenantId: UUID, namespace: String, operationKey: String): InventoryMovement? =
        movements.values.firstOrNull { it.tenantId == tenantId && it.namespace == namespace && it.operationKey == operationKey }

    @Synchronized
    override fun findById(movementId: UUID): InventoryMovement? = movements[movementId]

    @Synchronized
    override fun findAll(tenantId: UUID): List<InventoryMovement> = movements.values.filter { it.tenantId == tenantId }

    /**
     * Meniru urutan adapter aslinya: TERBARU DULU. Kalau ganda ini mengembalikan urutan
     * sisip, tes paginasi akan lolos di sini lalu gagal di produksi hanya karena baris
     * pertama halaman satu ternyata mutasi tertua.
     */
    @Synchronized
    override fun findPage(tenantId: UUID, filter: MovementFilter, page: PageRequest): Page<InventoryMovement> {
        val matched = movements.values
            .filter { it.tenantId == tenantId }
            .filter { filter.kind == null || it.kind == filter.kind }
            .filter { filter.state == null || it.state == filter.state }
            .filter { filter.itemId == null || it.legs.any { leg -> leg.itemId == filter.itemId } }
            .filter { filter.locationId == null || it.legs.any { leg -> leg.locationId == filter.locationId } }
            .filter { filter.from == null || !it.serverReceivedAt.isBefore(filter.from) }
            .filter { filter.until == null || it.serverReceivedAt.isBefore(filter.until) }
            .sortedWith(compareByDescending<InventoryMovement> { it.serverReceivedAt }.thenByDescending { it.movementId })
        return Page(
            matched.drop(page.page * page.size).take(page.size),
            page.page,
            page.size,
            matched.size.toLong(),
        )
    }

    @Synchronized
    override fun appendIfAbsent(movement: InventoryMovement): InventoryMovement? {
        val existing = findByOperation(movement.tenantId, movement.namespace, movement.operationKey)
        if (existing != null) return existing
        movements[movement.movementId] = movement
        return null
    }

    @Synchronized
    override fun updateState(movementId: UUID, state: MovementState): InventoryMovement {
        val updated = movements.getValue(movementId).copy(state = state)
        movements[movementId] = updated
        return updated
    }

    @Synchronized
    override fun balances(tenantId: UUID): List<InventoryBalance> = balances.entries
        .filter { it.key.tenantId == tenantId && it.value != 0 }
        .map { (key, quantity) ->
            InventoryBalance(
                key.tenantId, key.itemId, skuOfKey.getValue(key), key.locationId,
                key.ownerId, key.ownerKind, key.status, quantity,
            )
        }

    @Synchronized
    override fun applyLegs(tenantId: UUID, legs: List<MovementLeg>, at: Instant) {
        legs.forEach { leg ->
            val key = BalanceKey(tenantId, leg.itemId, leg.locationId, leg.custodyOwnerId, leg.custodyOwnerKind, leg.status)
            skuOfKey.putIfAbsent(key, leg.skuId)
            balances[key] = (balances[key] ?: 0) + if (leg.direction == LegDirection.IN) leg.quantity else -leg.quantity
        }
    }

    @Synchronized
    override fun rebuildBalances(tenantId: UUID, at: Instant): List<InventoryBalance> {
        balances.keys.filter { it.tenantId == tenantId }.forEach { balances.remove(it) }
        movements.values
            .filter { it.tenantId == tenantId && it.state == MovementState.APPLIED }
            .forEach { applyLegs(tenantId, it.legs, at) }
        return balances(tenantId)
    }

    /** Dimensi saldo tanpa `skuId` — sama dengan UNIQUE `inventory_balance_dimension_uq`. */
    private data class BalanceKey(
        val tenantId: UUID,
        val itemId: UUID,
        val locationId: UUID,
        val ownerId: UUID,
        val ownerKind: OwnerKind,
        val status: InventoryStatus,
    )
}

class FakeInventoryApprovals : InventoryApprovalRepository {
    private val requests = linkedMapOf<UUID, InventoryApprovalRequest>()
    private val effects = mutableListOf<InventoryApprovalEffect>()
    private val delegations = mutableListOf<ApproverDelegation>()

    override fun findById(approvalId: UUID): InventoryApprovalRequest? = requests[approvalId]

    override fun findByOperation(tenantId: UUID, operationKey: String): InventoryApprovalRequest? =
        requests.values.firstOrNull { it.tenantId == tenantId && it.operationKey == operationKey }

    override fun findPending(tenantId: UUID): List<InventoryApprovalRequest> =
        requests.values.filter { it.tenantId == tenantId && it.status == InventoryApprovalStatus.PENDING }

    override fun appendIfAbsent(request: InventoryApprovalRequest): InventoryApprovalRequest? {
        findByOperation(request.tenantId, request.operationKey)?.let { return it }
        requests[request.approvalId] = request
        return null
    }

    override fun updateStatus(approvalId: UUID, request: InventoryApprovalRequest) {
        requests[approvalId] = request
    }

    override fun markExpired(approvalId: UUID, revision: Long) {
        val current = requests[approvalId] ?: return
        if (current.status != InventoryApprovalStatus.PENDING) return
        requests[approvalId] = current.copy(status = InventoryApprovalStatus.EXPIRED, revision = revision)
    }

    override fun appendDecision(approvalId: UUID, snapshot: InventoryApprovalDecisionSnapshot) {
        val current = requests[approvalId] ?: return
        requests[approvalId] = current.copy(decisions = current.decisions + snapshot)
    }

    override fun delegations(tenantId: UUID): List<ApproverDelegation> = delegations.toList()

    override fun saveDelegation(delegation: ApproverDelegation) {
        delegations.removeIf { it.approverId == delegation.approverId && it.delegateId == delegation.delegateId }
        delegations += delegation
    }

    override fun recordEffect(effect: InventoryApprovalEffect): Boolean {
        if (effects.any { it.tenantId == effect.tenantId && it.approvalId == effect.approvalId }) return false
        effects += effect
        return true
    }

    override fun effects(tenantId: UUID): List<InventoryApprovalEffect> = effects.filter { it.tenantId == tenantId }
}

/**
 * Matriks persetujuan in-memory. Meniru UPSERT per (tenant, jenis) — tier lama DIGANTI,
 * bukan digabung, persis seperti adapter aslinya; ganda yang menggabung akan menyembunyikan
 * bug "tier yang dihapus operator ternyata masih ikut menyetujui".
 */
class FakeInventoryApprovalPolicies : InventoryApprovalPolicyRepository {
    private val rows = linkedMapOf<Pair<UUID, InventoryApprovalType>, InventoryApprovalPolicyMatrix>()

    override fun find(tenantId: UUID, type: InventoryApprovalType): InventoryApprovalPolicyMatrix? = rows[tenantId to type]

    override fun findAll(tenantId: UUID): List<InventoryApprovalPolicyMatrix> =
        rows.values.filter { it.tenantId == tenantId }

    override fun save(matrix: InventoryApprovalPolicyMatrix): InventoryApprovalPolicyMatrix {
        val stored = matrix.copy(version = (rows[matrix.tenantId to matrix.type]?.version ?: 0) + 1)
        rows[matrix.tenantId to matrix.type] = stored
        return stored
    }
}

/** Jejak override darurat in-memory; menolak jejak kedua untuk approval yang sama (UNIQUE V179). */
class FakeInventoryApprovalAudit : InventoryApprovalAuditRepository {
    val entries = mutableListOf<EmergencyOverrideAudit>()

    override fun recordEmergency(entry: EmergencyOverrideAudit): Boolean {
        if (entries.any { it.tenantId == entry.tenantId && it.approvalId == entry.approvalId }) return false
        entries += entry
        return true
    }

    override fun emergencyOverrides(tenantId: UUID): List<EmergencyOverrideAudit> =
        entries.filter { it.tenantId == tenantId }
}

class FakeMaterialFacts : MaterialConsumptionRepository {
    private val rows = mutableListOf<Pair<String, RecordedMaterialFact>>()

    override fun findByOperation(tenantId: UUID, operationKey: String): RecordedMaterialFact? =
        rows.firstOrNull { it.first == operationKey && it.second.fact.tenantId == tenantId }?.second

    override fun appendIfAbsent(fact: CustomerMaterialFact, operationKey: String, payloadHash: String): RecordedMaterialFact? {
        findByOperation(fact.tenantId, operationKey)?.let { return it }
        rows += operationKey to RecordedMaterialFact(fact, payloadHash)
        return null
    }

    override fun forCustomer(tenantId: UUID, customerId: UUID): List<CustomerMaterialFact> =
        rows.map { it.second.fact }.filter { it.tenantId == tenantId && it.customerId == customerId }
}

class FakeCycleCounts : InventoryReconciliationRepository {
    private val rows = linkedMapOf<UUID, CycleCount>()

    override fun save(count: CycleCount): CycleCount = count.also { rows[it.countId] = it }

    override fun findById(countId: UUID): CycleCount? = rows[countId]

    override fun find(tenantId: UUID, countId: UUID): CycleCount? = rows[countId]?.takeIf { it.tenantId == tenantId }

    override fun findByOperation(tenantId: UUID, operationKey: String): CycleCount? =
        rows.values.firstOrNull { it.tenantId == tenantId && it.operationKey == operationKey }

    override fun findOpen(tenantId: UUID): List<CycleCount> = rows.values.filter {
        it.tenantId == tenantId && (it.discrepancy == DiscrepancyState.OPEN || it.discrepancy == DiscrepancyState.REWORK_REQUIRED)
    }
}

/** Aset serial in-memory, dipakai uji idempotency `linkInstalledOnu`. */
class FakeSerializedAssets : com.duluin.ftth.inventory.application.port.outbound.SerializedAssetRepository {
    private val rows = linkedMapOf<UUID, SerializedAsset>()
    private val operationKeys = mutableMapOf<Pair<UUID, String>, UUID>()

    /** Berapa kali efek tulis benar-benar terjadi — dipakai membuktikan replay tidak menulis ulang. */
    var saveCount: Int = 0
        private set

    override fun findById(id: UUID): SerializedAsset? = rows[id]

    override fun findAll(tenantId: UUID): List<SerializedAsset> = rows.values.filter { it.tenantId == tenantId }

    override fun findBySerial(tenantId: UUID, serialNumber: String): SerializedAsset? =
        rows.values.firstOrNull { it.tenantId == tenantId && it.serialNumber == serialNumber }

    override fun findByMac(tenantId: UUID, macAddress: String): SerializedAsset? =
        rows.values.firstOrNull { it.tenantId == tenantId && it.macAddress == macAddress }

    override fun save(asset: SerializedAsset, operationKey: String?): SerializedAsset {
        saveCount++
        rows[asset.id] = asset
        if (operationKey != null) operationKeys[asset.tenantId to operationKey] = asset.id
        return asset
    }

    override fun delete(assetId: UUID) {
        rows.remove(assetId)
    }

    override fun existsHistoricalSerial(tenantId: UUID, serialNumber: String) = findBySerial(tenantId, serialNumber) != null

    override fun existsHistoricalMac(tenantId: UUID, macAddress: String) = findByMac(tenantId, macAddress) != null

    override fun findByOperation(tenantId: UUID, operationKey: String): SerializedAsset? =
        operationKeys[tenantId to operationKey]?.let { rows[it] }
}
