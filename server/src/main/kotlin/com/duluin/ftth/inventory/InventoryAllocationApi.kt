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

    // ------------------------------------------------- Penarikan aset (P2.6)

    /**
     * Tarik satu unit BERSERIAL dari pelanggan ke van stock teknisi (WO DISMANTLE).
     *
     * Arahnya kebalikan dari [issueMaterial]/[scanMaterialSerial]: yang ini tidak mengambil
     * apa pun dari gudang, ia MENGEMBALIKAN barang yang bertahun-tahun lalu sudah dikonsumsi.
     * Karena itu ia TIDAK lewat [WorkOrderMaterialView] — baris material memodelkan "diambil
     * dari gudang untuk WO ini", dan memaksa aset tarikan ke sana membuat
     * `plannedQuantity`/`issuedQuantity` tak bermakna (lihat V197).
     *
     * Saldo TIDAK bergerak di sini. Yang terjadi hanya pencatatan; pemotongan/penambahan
     * saldonya baru komitmen saat WO-nya DISETUJUI, lewat saga fulfillment yang sama dengan
     * material biasa. Persetujuan WO itulah mata keduanya (D7).
     */
    fun recoverAsset(command: RecoverWorkOrderAssetCommand): WorkOrderRecoveredAssetView

    /** Termasuk baris yang sudah dibatalkan — jejaknya justru yang menjelaskan kenapa saldo tak bertambah. */
    fun recoveredAssets(tenantId: UUID, workOrderId: UUID): List<WorkOrderRecoveredAssetView>

    /**
     * Batalkan satu baris penarikan (salah scan).
     *
     * Penanda batal, bukan penghapusan: baris yang dibatalkan berhenti ikut dipotong saga tapi
     * tetap bisa menjawab "kenapa unit ini pernah tercatat ditarik lalu tidak jadi".
     */
    fun cancelRecoveredAsset(command: CancelWorkOrderRecoveredAssetCommand): WorkOrderRecoveredAssetView

    /**
     * Van (lokasi van stock) milik tenant ini — HANYA id dan kodenya.
     *
     * Ada supaya teknisi punya cara menemukan `technicianLocationId` yang WAJIB disertakan
     * [RecoverWorkOrderAssetCommand] tanpa harus memegang `inventory.location.view`. Lihat
     * KDoc endpoint `/van-locations` di `WorkOrderMaterialController` untuk alasan lengkapnya.
     *
     * Sengaja BUKAN `List<InventoryLocationView>`: begitu bentuknya sama dengan master data
     * gudang, lambat laun ia akan ikut membawa `parentId`, jenis, dan atribut gudang lain —
     * dan permukaan sempit ini berubah jadi salinan kedua `/api/inventory/locations` yang
     * dijaga izin yang jauh lebih lemah.
     */
    fun vanLocations(tenantId: UUID): List<WorkOrderVanLocationView>
}

/**
 * Permintaan menarik satu unit dari pelanggan.
 *
 * [technicianId] + [technicianLocationId] WAJIB, meniru [IssueWorkOrderMaterialCommand] (D6c):
 * barang yang dicabut dari rumah pelanggan langsung ada di tangan seseorang, dan saldo yang
 * bertambah saat approval harus mendarat di van stock ORANG ITU. Tanpa keduanya, penambahan
 * saldo jatuh ke dimensi yang tak pernah diisi dan unitnya kembali jadi barang yang ada di
 * pembukuan tapi tidak ada pemegangnya.
 */
data class RecoverWorkOrderAssetCommand(
    val tenantId: UUID,
    val workOrderId: UUID,
    val actorId: UUID,
    val customerId: UUID,
    val serialNumber: String,
    val technicianId: UUID,
    val technicianLocationId: UUID,
    /** GOOD / DAMAGED. */
    val condition: String = "GOOD",
    val note: String? = null,
)

data class CancelWorkOrderRecoveredAssetCommand(
    val tenantId: UUID,
    val workOrderId: UUID,
    val recoveredAssetId: UUID,
    val actorId: UUID,
    val reason: String? = null,
)

data class WorkOrderRecoveredAssetView(
    val id: UUID,
    val workOrderId: UUID,
    val assetId: UUID,
    val serialNumber: String,
    val macAddress: String?,
    val itemId: UUID,
    val itemCode: String,
    val itemName: String,
    val itemCategory: String,
    val customerId: UUID,
    val technicianId: UUID,
    /** Nama teknisi pembawa unit tarikan; fallback ke UUID bila id-nya tak teresolusi lagi. */
    val technicianName: String,
    val technicianLocationId: UUID,
    /**
     * Kode van tempat unit ini mendarat; fallback ke UUID-nya bila lokasinya sudah terhapus.
     *
     * TIDAK disimpan di baris penarikan dan itu disengaja: nilai tersimpan akan beku saat van
     * yang sama berganti kode, dan dua baris untuk van yang sama akan berbunyi beda tanpa ada
     * satu pun peristiwa yang menjelaskannya. Diresolusi per permintaan, sekali untuk seluruh
     * daftar (lihat `WorkOrderAssetRecoveryService.locationCodes`).
     */
    val technicianLocationCode: String,
    val condition: String,
    val note: String?,
    val recoveredAt: Instant,
    val recoveredBy: UUID,
    /** Nama peng-scan penarikan; fallback ke UUID bila id-nya tak teresolusi lagi. */
    val recoveredByName: String,
    val cancelledAt: Instant?,
    val cancelledBy: UUID?,
    /** `null` HANYA bila barisnya memang belum dibatalkan ([cancelledBy] kosong). */
    val cancelledByName: String?,
    val cancelReason: String?,
)

/**
 * Satu van yang boleh dipilih teknisi sebagai tujuan unit tarikan.
 *
 * DUA bidang, titik. Yang dibutuhkan layar penarikan cuma "mana yang kupilih" (kode) dan "apa
 * yang kukirim" (id); segala tambahan di sini — jenis, induk, kapasitas — adalah master data
 * gudang yang bocor lewat izin material work order, persis yang dihindari endpoint ini.
 */
data class WorkOrderVanLocationView(val id: UUID, val code: String)

data class PlannedMaterialLineInput(val itemId: UUID, val quantity: Int)

data class PlanWorkOrderMaterialCommand(
    val tenantId: UUID,
    val workOrderId: UUID,
    val workOrderType: String,
    /**
     * Boleh `null` HANYA saat [clear] — pengosongan cuma menghapus baris dan tidak melahirkan
     * satu pun efek saga. Membuat baris BARU tetap menuntutnya (`inventory_fulfillment_effect
     * .customer_id` NOT NULL), dan penjaganya ada di `planMaterial` tepat di titik pembuatan.
     */
    val customerId: UUID?,
    val actorId: UUID,
    val lines: List<PlannedMaterialLineInput> = emptyList(),
    /**
     * `true` = BUANG seluruh rencana. Sengaja bidang tersendiri, bukan disimpulkan dari [lines]
     * yang kosong: daftar kosong sudah punya arti lain sejak awal ("pakai BOM apa adanya"), dan
     * dispatcher yang menghapus baris terakhir dari layar lalu menyimpan justru akan mendapat
     * rencana PENUH kembali. Dua maksud yang berlawanan tidak boleh memakai bentuk yang sama.
     *
     * Saat `true`, [lines] diabaikan seluruhnya.
     */
    val clear: Boolean = false,
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
    /**
     * Nama peng-scan, sudah teresolusi di server.
     *
     * Fallback-nya UUID, BUKAN string kosong: sel kosong terbaca "tidak ada orangnya", padahal
     * yang terjadi adalah id yang tak teresolusi (pengguna terhapus). UUID jelek itu petunjuk.
     */
    val scannedByName: String,
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
    /**
     * Nama teknisi pemegang barisnya; `null` HANYA bila [technicianId] memang belum terisi
     * (baris yang belum pernah keluar gudang). Id yang ada tapi tak teresolusi jatuh ke UUID-nya.
     */
    val technicianName: String?,
    val technicianLocationId: UUID?,
    val varianceReason: String?,
    val serials: List<WorkOrderMaterialSerialView>,
)
