package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.CycleCount
import com.duluin.ftth.inventory.domain.model.InventoryMovement
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Permukaan TULIS gudang: terima barang, minta restock, transfer, keluarkan, retur,
 * penyesuaian, dan stock opname.
 *
 * Setiap operasi butuh `operationKey` + `payloadHash` dari klien. Itu bukan formalitas:
 * petugas gudang bekerja di jaringan yang putus-nyambung dan akan menekan tombol dua kali
 * setiap kali layarnya diam. Tanpa kunci operasi, tekanan kedua menjadi pengeluaran barang
 * kedua — dan selisihnya baru ketahuan saat stok fisik diadu dengan sistem.
 *
 * Izinnya dipisah per aksi (`inventory.movement.transfer` / `.issue` / `.return` / `.adjust`)
 * karena keempatnya dipegang orang yang berbeda di gudang sungguhan; menyatukannya berarti
 * siapa pun yang boleh menggeser barang antar rak juga boleh menyerahkannya ke teknisi —
 * atau menghapusbukukannya.
 *
 * CATATAN untuk tenant yang merakit peran sendiri: transfer dulu ikut
 * `inventory.movement.issue`. Peran bawaan ("Tenant Admin" dan Super Admin) otomatis ikut
 * mendapat izin baru ini karena keduanya di-backfill seluruh katalog tiap boot, tapi peran
 * custom yang dulu sengaja hanya diberi `inventory.movement.issue` kehilangan hak transfernya
 * dan HARUS ditambahi izin baru ini secara manual.
 */
@RestController
@RequestMapping("/api/inventory")
class InventoryOperationsController(
    private val operations: InventoryOperationsService,
    private val counts: InventoryReconciliationService,
    private val currentUser: CurrentUserProvider,
) {
    @PostMapping("/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('inventory.restock.receive')")
    fun receive(@Valid @RequestBody body: GoodsReceiptBody): InventoryMovement {
        val actor = currentUser.current()
        return operations.receive(body.toCommand(actor.tenantId, actor.userId))
    }

    @PostMapping("/restock-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('inventory.restock.request')")
    fun requestRestock(@Valid @RequestBody body: GoodsReceiptBody): InventoryOperationResult {
        val actor = currentUser.current()
        return operations.requestRestock(body.toCommand(actor.tenantId, actor.userId))
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('inventory.movement.transfer')")
    fun transfer(@Valid @RequestBody body: TransferBody): InventoryMovement {
        val actor = currentUser.current()
        return operations.transfer(body.toCommand(actor.tenantId, actor.userId))
    }

    @PostMapping("/issues")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('inventory.movement.issue')")
    fun issue(@Valid @RequestBody body: IssueBody): InventoryMovement {
        val actor = currentUser.current()
        return operations.issue(body.toCommand(actor.tenantId, actor.userId))
    }

    @PostMapping("/returns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('inventory.movement.return')")
    fun returnGoods(@Valid @RequestBody body: ReturnBody): InventoryMovement {
        val actor = currentUser.current()
        return operations.returnToWarehouse(body.toCommand(actor.tenantId, actor.userId))
    }

    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('inventory.movement.adjust')")
    fun adjust(@Valid @RequestBody body: AdjustmentBody): InventoryOperationResult {
        val actor = currentUser.current()
        return operations.adjust(body.toCommand(actor.tenantId, actor.userId))
    }

    @PostMapping("/counts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('inventory.count.perform')")
    fun count(@Valid @RequestBody body: CycleCountBody): CycleCount {
        val actor = currentUser.current()
        return counts.createCount(body.toCommand(actor.tenantId))
    }

    @GetMapping("/counts/open")
    @PreAuthorize("@authz.can('inventory.count.perform')")
    fun openCounts(): List<CycleCount> = counts.open(currentUser.current().tenantId)

    /**
     * Pengesahan selisih opname. Empat mata dijaga di domain: pemegang barang tidak bisa
     * mengesahkan selisihnya sendiri, kalau tidak barang yang hilang bisa "diputihkan" oleh
     * orang yang sama yang memegangnya.
     */
    @PostMapping("/counts/{id}/approval")
    @PreAuthorize("@authz.can('inventory.count.approve')")
    fun approveCount(@PathVariable id: UUID, @Valid @RequestBody body: CountApprovalBody): CycleCount {
        val actor = currentUser.current()
        return counts.approveVariance(id, actor.userId, body.operationKey, body.payloadHash)
    }
}

data class GoodsReceiptBody(
    val locationId: UUID,
    val custodianId: UUID,
    @field:NotEmpty val lines: List<StockLineBody>,
    @field:NotBlank val reason: String,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val payloadHash: String,
    val emergencyReason: String? = null,
) {
    fun toCommand(tenantId: UUID, actorId: UUID) = GoodsReceiptCommand(
        tenantId, actorId, locationId, custodianId, lines.map { it.toLine() }, reason, operationKey, payloadHash, emergencyReason,
    )
}

data class TransferBody(
    val fromLocationId: UUID,
    val fromCustodianId: UUID,
    val toLocationId: UUID,
    val toCustodianId: UUID,
    @field:NotEmpty val lines: List<StockLineBody>,
    @field:NotBlank val reason: String,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val payloadHash: String,
) {
    fun toCommand(tenantId: UUID, actorId: UUID) = TransferCommand(
        tenantId, actorId, fromLocationId, fromCustodianId, toLocationId, toCustodianId,
        lines.map { it.toLine() }, reason, operationKey, payloadHash,
    )
}

data class IssueBody(
    val fromLocationId: UUID,
    val custodianId: UUID,
    val technicianId: UUID,
    val technicianLocationId: UUID,
    @field:NotEmpty val lines: List<StockLineBody>,
    @field:NotBlank val reason: String,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val payloadHash: String,
) {
    fun toCommand(tenantId: UUID, actorId: UUID) = IssueCommand(
        tenantId, actorId, fromLocationId, custodianId, technicianId, technicianLocationId,
        lines.map { it.toLine() }, reason, operationKey, payloadHash,
    )
}

data class ReturnBody(
    val fromLocationId: UUID,
    val technicianId: UUID,
    val toLocationId: UUID,
    val custodianId: UUID,
    val quarantine: Boolean = false,
    @field:NotEmpty val lines: List<StockLineBody>,
    @field:NotBlank val reason: String,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val payloadHash: String,
) {
    fun toCommand(tenantId: UUID, actorId: UUID) = ReturnCommand(
        tenantId, actorId, fromLocationId, technicianId, toLocationId, custodianId, quarantine,
        lines.map { it.toLine() }, reason, operationKey, payloadHash,
    )
}

data class AdjustmentBody(
    val locationId: UUID,
    val custodianId: UUID,
    val kind: AdjustmentKind,
    val increase: Boolean = false,
    @field:NotEmpty val lines: List<StockLineBody>,
    @field:NotBlank val reason: String,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val payloadHash: String,
    val emergencyReason: String? = null,
) {
    fun toCommand(tenantId: UUID, actorId: UUID) = AdjustmentCommand(
        tenantId, actorId, locationId, custodianId, kind, increase,
        lines.map { it.toLine() }, reason, operationKey, payloadHash, emergencyReason,
    )
}

data class CycleCountBody(
    val locationId: UUID,
    val itemId: UUID,
    @field:PositiveOrZero val observedQuantity: Int,
    val custodianId: UUID,
    @field:NotBlank val reason: String,
    @field:NotBlank val evidenceReference: String,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val payloadHash: String,
) {
    /**
     * `skuId` diisi `itemId`: sejak `Sku` dihapus dari domain, master data barang HANYA
     * `inventory_item`. Membiarkan klien mengirim sku terpisah akan menghidupkan lagi id
     * kedua yang tidak dimiliki tabel mana pun.
     */
    fun toCommand(tenantId: UUID) = CycleCountCommand(
        tenantId, tenantId, locationId, itemId, itemId, observedQuantity, custodianId,
        reason, evidenceReference, operationKey, payloadHash,
    )
}

data class CountApprovalBody(@field:NotBlank val operationKey: String, @field:NotBlank val payloadHash: String)
