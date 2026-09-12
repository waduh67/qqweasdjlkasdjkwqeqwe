package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

/**
 * Kontrak SINKRON `workorder -> inventory` untuk material yang dibawa teknisi.
 *
 * Kenapa sinkron, padahal sudah ada saga fulfillment? Karena dua pertanyaan itu berbeda:
 *
 *  * "Apakah gudang PUNYA barangnya sekarang?" harus dijawab SEKETIKA saat barang dikeluarkan
 *    — teknisi berdiri di depan loket, dan jawaban "nanti diproses" berarti dia berangkat
 *    dengan tas kosong atau dengan barang yang tidak pernah tercatat keluar.
 *  * "Potong saldonya secara permanen" adalah komitmen akhir, dan itu memang lewat saga:
 *    tahan banting terhadap restart, idempoten, dan bisa diulang. Lihat [InventoryApi.fulfillmentAllocations].
 *
 * SELURUH pergerakan stok di balik kontrak ini tetap bermuara ke `InventoryOperationsService`
 * dan ledger. Modul `workorder` TIDAK PERNAH menulis ledger sendiri — kalau ia boleh,
 * gudang punya dua jalan masuk dan salah satunya cepat atau lambat lupa memvalidasi saldo.
 *
 * Semua DTO di sini SENGAJA hanya memakai tipe primitif, UUID, dan Instant. Membocorkan enum
 * dari `inventory.domain.model` akan membuat modul pemanggil bergantung pada package internal
 * dan `ModularityTests` menolaknya.
 */
interface InventoryAllocationApi {
    /** BOM jenis WO ini — dipakai layar rencana material untuk mempra-isi daftarnya. */
    fun materialTemplate(tenantId: UUID, workOrderType: String): List<WorkOrderMaterialTemplateView>

    fun saveMaterialTemplate(command: SaveMaterialTemplateCommand): List<WorkOrderMaterialTemplateView>

    fun materials(tenantId: UUID, workOrderId: UUID): List<WorkOrderMaterialView>

    /** Susun/ganti rencana material. Daftar kosong berarti "pakai BOM apa adanya". */
    fun planMaterial(command: PlanWorkOrderMaterialCommand): List<WorkOrderMaterialView>

    /** Keluarkan barang dari gudang ke van stock teknisi. Gagal SEKETIKA kalau stok kurang. */
    fun issueMaterial(command: IssueWorkOrderMaterialCommand): List<WorkOrderMaterialView>

    fun recordMaterialUsage(command: RecordWorkOrderMaterialUsageCommand): WorkOrderMaterialView

    fun scanMaterialSerial(command: ScanWorkOrderMaterialSerialCommand): WorkOrderMaterialView

    /**
     * Melempar kalau WO ini belum layak ditutup: masih ada unit berserial yang belum di-scan,
     * barang curah yang belum dilaporkan nasibnya, atau selisih terhadap rencana tanpa alasan.
     *
     * WO tanpa material sama sekali lolos tanpa suara — tidak semua WO membawa barang.
     */
    fun assertMaterialReadyForCompletion(tenantId: UUID, workOrderId: UUID)

    /**
     * Apakah WO ini punya material yang benar-benar bisa dipotong saga?
     *
     * Dipakai `WorkOrderService.approve` untuk memutuskan apakah efek `INVENTORY` diminta.
     * Jawabannya SENGAJA "ada alokasi yang bisa dikonsumsi", bukan sekadar "ada baris
     * material": preflight saga menolak WO yang meminta efek INVENTORY tapi alokasinya
     * kosong dengan `INVENTORY_ALLOCATIONS_NOT_FOUND`, dan penolakan itu mematikan SELURUH
     * approval WO-nya, bukan hanya bagian gudangnya.
     */
    fun hasFulfillableMaterial(tenantId: UUID, workOrderId: UUID): Boolean
}

data class PlannedMaterialLineInput(val itemId: UUID, val quantity: Int)

data class PlanWorkOrderMaterialCommand(
    val tenantId: UUID,
    val workOrderId: UUID,
    val workOrderType: String,
    val customerId: UUID,
    val actorId: UUID,
    val lines: List<PlannedMaterialLineInput> = emptyList(),
)

data class IssuedMaterialLineInput(
    val itemId: UUID,
    val quantity: Int,
    /** Wajib dan harus sebanyak [quantity] kalau itemnya berserial; kosong kalau curah. */
    val serialNumbers: List<String> = emptyList(),
)

data class IssueWorkOrderMaterialCommand(
    val tenantId: UUID,
    val workOrderId: UUID,
    val actorId: UUID,
    val fromLocationId: UUID,
    val custodianId: UUID,
    val technicianId: UUID,
    val technicianLocationId: UUID,
    val lines: List<IssuedMaterialLineInput>,
    val reason: String,
    val operationKey: String,
    val payloadHash: String,
)

data class RecordWorkOrderMaterialUsageCommand(
    val tenantId: UUID,
    val workOrderId: UUID,
    val actorId: UUID,
    val itemId: UUID,
    val usedQuantity: Int,
    val returnedQuantity: Int = 0,
    val lostQuantity: Int = 0,
    val varianceReason: String? = null,
)

data class ScanWorkOrderMaterialSerialCommand(
    val tenantId: UUID,
    val workOrderId: UUID,
    val actorId: UUID,
    val serialNumber: String,
    /** INSTALLED / RETURNED / LOST. */
    val outcome: String,
    val macAddress: String? = null,
)

data class MaterialTemplateLineInput(val itemId: UUID, val plannedQuantity: Int, val note: String? = null)

data class SaveMaterialTemplateCommand(
    val tenantId: UUID,
    val workOrderType: String,
    val lines: List<MaterialTemplateLineInput>,
)

data class WorkOrderMaterialTemplateView(
    val itemId: UUID,
    val itemCode: String,
    val itemName: String,
    val itemCategory: String,
    val unit: String,
    val serialized: Boolean,
    val plannedQuantity: Int,
    val note: String?,
)

data class WorkOrderMaterialSerialView(
    val id: UUID,
    val assetId: UUID,
    val serialNumber: String,
    val macAddress: String?,
    val outcome: String,
    val scannedAt: Instant,
    val scannedBy: UUID,
)

data class WorkOrderMaterialView(
    val id: UUID,
    val workOrderId: UUID,
    val itemId: UUID,
    val itemCode: String,
    val itemName: String,
    val itemCategory: String,
    val unit: String,
    val serialized: Boolean,
    /** Jumlah menurut BOM saat rencana dibuat; null berarti item di luar template. */
    val templateQuantity: Int?,
    val plannedQuantity: Int,
    val issuedQuantity: Int,
    val usedQuantity: Int,
    val returnedQuantity: Int,
    val lostQuantity: Int,
    /** Unit berserial yang sudah keluar gudang tapi belum di-scan nasibnya. */
    val unscannedQuantity: Int,
    val technicianId: UUID?,
    val technicianLocationId: UUID?,
    val varianceReason: String?,
    val serials: List<WorkOrderMaterialSerialView>,
)
