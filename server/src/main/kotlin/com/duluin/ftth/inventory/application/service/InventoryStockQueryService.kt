package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.inventory.application.port.outbound.InventoryItemRepository
import com.duluin.ftth.inventory.application.port.outbound.InventoryLocationRepository
import com.duluin.ftth.inventory.application.port.outbound.MovementFilter
import com.duluin.ftth.inventory.application.port.outbound.SerializedAssetRepository
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Pertanyaan yang selalu diajukan orang gudang tapi belum punya jawaban di sistem ini:
 * "mutasi apa saja yang terjadi", "berapa sisa barang X di gudang Y", "apa saja yang dibawa
 * teknisi Z", dan "di mana angkanya tidak cocok".
 *
 * Semua id barang dan lokasi DITERJEMAHKAN jadi kode/nama di sini. Mengembalikan UUID
 * telanjang membuat layar gudang mustahil dibaca dan memaksa web memanggil dua endpoint
 * tambahan hanya untuk menampilkan satu baris.
 */
@Service
class InventoryStockQueryService(
    private val ledger: InventoryMovementLedgerService,
    private val items: InventoryItemRepository,
    private val locations: InventoryLocationRepository,
    private val assets: SerializedAssetRepository,
    private val reconciliation: InventoryReconciliationService,
) {

    @Transactional(readOnly = true)
    fun ledgerPage(tenantId: UUID, filter: MovementFilter, page: PageRequest): Page<MovementEntryView> {
        val names = names(tenantId)
        return ledger.movementPage(tenantId, filter, page).map { it.toView(names) }
    }

    @Transactional(readOnly = true)
    fun balances(tenantId: UUID, itemId: UUID? = null, locationId: UUID? = null): List<StockBalanceView> {
        val names = names(tenantId)
        return ledger.balances(tenantId)
            .filter { (itemId == null || it.itemId == itemId) && (locationId == null || it.locationId == locationId) }
            .map { it.toView(names) }
            .sortedWith(compareBy({ it.itemCode }, { it.locationCode }, { it.status }))
    }

    /**
     * Van stock: barang yang sedang dipegang teknisi, bukan yang ada di gudang.
     *
     * Disaring lewat `custodyOwnerKind = TECHNICIAN`, BUKAN lewat jenis lokasinya: teknisi
     * yang sedang berada di gudang tetap memegang barangnya sendiri, dan menyaring pakai
     * lokasi akan membuat isi mobilnya menghilang dari laporan setiap kali ia mampir.
     */
    @Transactional(readOnly = true)
    fun vanStock(tenantId: UUID, technicianId: UUID? = null): List<VanStockView> {
        val names = names(tenantId)
        val quantities = ledger.balances(tenantId)
            .filter { it.custodyOwnerKind == OwnerKind.TECHNICIAN && (technicianId == null || it.custodyOwnerId == technicianId) }
        val serials = assets.findAll(tenantId)
            .filter { it.custody.ownerKind == OwnerKind.TECHNICIAN && (technicianId == null || it.custody.ownerId == technicianId) }
            .groupBy { it.custody.ownerId to it.skuId }
        return quantities
            .groupBy { it.custodyOwnerId }
            .map { (owner, rows) ->
                VanStockView(
                    owner,
                    rows.map { row ->
                        VanStockLineView(
                            row.itemId, names.item(row.itemId), row.locationId, names.location(row.locationId),
                            row.status, row.quantity,
                            serials[owner to row.itemId].orEmpty().filter { it.status == row.status }.map { it.serialNumber }.sorted(),
                        )
                    }.sortedWith(compareBy({ it.itemCode }, { it.status })),
                )
            }
            .sortedBy { it.technicianId.toString() }
    }

    /**
     * Laporan selisih: stock opname yang masih menggantung plus saldo yang tidak masuk akal.
     *
     * Keduanya digabung SENGAJA — selisih yang ditemukan manusia (opname) dan selisih yang
     * ditemukan sistem (saldo negatif atau tak cocok dengan daftar aset) harus muncul di satu
     * layar. Kalau dipisah, tidak ada seorang pun yang membuka keduanya setiap hari.
     */
    @Transactional(readOnly = true)
    fun varianceReport(tenantId: UUID): VarianceReportView {
        val names = names(tenantId)
        val open = reconciliation.open(tenantId).map {
            OpenCountView(
                it.countId, it.itemId, names.item(it.itemId), it.locationId, names.location(it.locationId),
                it.priorQuantity, it.observedQuantity, it.observedQuantity - it.priorQuantity,
                it.custodianId, it.discrepancy, it.createdAt,
            )
        }
        // Saldo per unit fisik harus cocok dengan jumlah baris aset serial yang berstatus sama:
        // kalau tidak, ada unit yang tercatat di salah satu sisi saja — persis bentuk kebocoran
        // yang paling sering luput karena kedua angkanya masing-masing terlihat wajar.
        val assetCounts = assets.findAll(tenantId)
            .groupingBy { Triple(it.skuId, it.locationId, it.status) }.eachCount()
        val mismatches = ledger.balances(tenantId).mapNotNull { balance ->
            val counted = assetCounts[Triple(balance.itemId, balance.locationId, balance.status)] ?: 0
            val serializedItem = counted > 0
            when {
                balance.quantity < 0 -> BalanceAnomalyView(
                    balance.itemId, names.item(balance.itemId), balance.locationId, names.location(balance.locationId),
                    balance.status, balance.quantity, counted, "saldo negatif",
                )
                serializedItem && counted != balance.quantity -> BalanceAnomalyView(
                    balance.itemId, names.item(balance.itemId), balance.locationId, names.location(balance.locationId),
                    balance.status, balance.quantity, counted, "saldo tidak cocok dengan jumlah aset serial",
                )
                else -> null
            }
        }
        return VarianceReportView(open, mismatches)
    }

    /**
     * Kamus kode barang dan lokasi, dibaca SEKALI per permintaan.
     *
     * Kalau setiap baris mencari namanya sendiri, satu halaman riwayat 20 baris dengan 40 leg
     * menghasilkan puluhan query tambahan — N+1 yang tumbuh persis seiring ramainya gudang.
     */
    private fun names(tenantId: UUID) = NameBook(
        items.findAll(tenantId).associate { it.id to it.code },
        locations.findAll(tenantId).associate { it.id to it.code },
    )

    private class NameBook(private val itemCodes: Map<UUID, String>, private val locationCodes: Map<UUID, String>) {
        fun item(id: UUID) = itemCodes[id] ?: "(tidak dikenal)"
        fun location(id: UUID) = locationCodes[id] ?: "(tidak dikenal)"
    }

    private fun InventoryMovement.toView(names: NameBook) = MovementEntryView(
        movementId, kind, state, reason, actorId, serverReceivedAt, operationKey, compensatesMovementId,
        legs.map { leg ->
            MovementLegView(
                leg.direction, leg.itemId, names.item(leg.itemId), leg.locationId, names.location(leg.locationId),
                leg.quantity, leg.status, leg.custodyOwnerId, leg.custodyOwnerKind, leg.assetId, leg.serialNumber,
            )
        },
    )

    private fun InventoryBalance.toView(names: NameBook) = StockBalanceView(
        itemId, names.item(itemId), locationId, names.location(locationId),
        custodyOwnerId, custodyOwnerKind, status, quantity,
    )
}

data class MovementEntryView(
    val movementId: UUID,
    val kind: MovementKind,
    val state: MovementState,
    val reason: String,
    val actorId: UUID,
    val occurredAt: java.time.Instant,
    val operationKey: String,
    val compensatesMovementId: UUID?,
    val legs: List<MovementLegView>,
)

data class MovementLegView(
    val direction: LegDirection,
    val itemId: UUID,
    val itemCode: String,
    val locationId: UUID,
    val locationCode: String,
    val quantity: Int,
    val status: InventoryStatus,
    val custodyOwnerId: UUID,
    val custodyOwnerKind: OwnerKind,
    val assetId: UUID?,
    val serialNumber: String?,
)

data class StockBalanceView(
    val itemId: UUID,
    val itemCode: String,
    val locationId: UUID,
    val locationCode: String,
    val custodyOwnerId: UUID,
    val custodyOwnerKind: OwnerKind,
    val status: InventoryStatus,
    val quantity: Int,
)

data class VanStockView(val technicianId: UUID, val lines: List<VanStockLineView>)

data class VanStockLineView(
    val itemId: UUID,
    val itemCode: String,
    val locationId: UUID,
    val locationCode: String,
    val status: InventoryStatus,
    val quantity: Int,
    val serialNumbers: List<String>,
)

data class OpenCountView(
    val countId: UUID,
    val itemId: UUID,
    val itemCode: String,
    val locationId: UUID,
    val locationCode: String,
    val priorQuantity: Int,
    val observedQuantity: Int,
    val delta: Int,
    val custodianId: UUID,
    val state: DiscrepancyState,
    val countedAt: java.time.Instant,
)

data class BalanceAnomalyView(
    val itemId: UUID,
    val itemCode: String,
    val locationId: UUID,
    val locationCode: String,
    val status: InventoryStatus,
    val projectedQuantity: Int,
    val serializedAssetCount: Int,
    val issue: String,
)

data class VarianceReportView(val openCounts: List<OpenCountView>, val anomalies: List<BalanceAnomalyView>)
