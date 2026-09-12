package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.domain.model.InventoryStatus
import java.util.UUID
import java.time.Instant

data class InventoryAssetRef(
    val assetId: UUID,
    val tenantId: UUID,
    val skuId: UUID,
    val serialNumber: String,
    val macAddress: String?,
    val status: InventoryStatus,
    val locationId: UUID,
    val custodyOwnerId: UUID,
    val installedOnuId: UUID?,
)

interface InventoryApi {
    fun findSerializedAsset(assetId: UUID): InventoryAssetRef?
    fun findBySerial(serialNumber: String): InventoryAssetRef?
    fun linkInstalledOnu(assetId: UUID, onuId: UUID, operationKey: String): InventoryAssetRef

    fun consumeFulfillment(command: InventoryFulfillmentCommand): InventoryFulfillmentResult
    fun returnFulfillment(command: InventoryFulfillmentCommand): InventoryFulfillmentResult
    fun fulfillmentAllocations(workOrderId: UUID): List<InventoryFulfillmentAllocation> = emptyList()
}

data class InventoryFulfillmentAllocation(
    val targetId: UUID,
    val itemId: UUID,
    val skuId: UUID,
    val locationId: UUID,
    val customerId: UUID,
    val quantity: Int,
    val serialized: Boolean,
    val actorId: UUID,
    val itemCategory: String,
    /** Unit fisik yang dialokasikan; WAJIB terisi kalau [serialized] (lihat V174). */
    val assetId: UUID? = null,
    val serialNumber: String? = null,
    /**
     * Arah alokasi: `false` = barang KELUAR dari saldo (dipakai di pelanggan), `true` = barang
     * MASUK kembali (unit yang ditarik dari rumah pelanggan saat WO DISMANTLE).
     *
     * SENGAJA sekadar satu bendera di daftar alokasi yang SAMA, bukan jalur efek tersendiri.
     * Satu WO DISMANTLE yang nyata lazimnya melakukan keduanya sekaligus — memakai patch cord
     * baru DAN menarik ONT lama — dan dua jalur terpisah berarti dua preflight, dua daftar
     * alokasi, dan dua skema idempotensi untuk satu persetujuan WO yang sama. Begitu keduanya
     * bisa berhasil sendiri-sendiri, ada keadaan di mana ONT-nya sudah masuk saldo sementara
     * patch cord-nya belum keluar, dan tidak ada satu pun checkpoint yang tahu itu separuh jadi.
     *
     * Tipe primitif dengan SENGAJA: kelas ini ada di package dasar modul `inventory`, dan
     * `ModularityTests` menolak DTO lintas-modul yang membocorkan enum internal.
     */
    val returned: Boolean = false,
)

data class InventoryFulfillmentCommand(
    val tenantId: UUID,
    val targetId: UUID,
    val itemId: UUID,
    val skuId: UUID,
    val locationId: UUID,
    val customerId: UUID,
    val workOrderId: UUID,
    val quantity: Int,
    val serialized: Boolean,
    val installed: Boolean,
    val actorId: UUID,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val reason: String,
    val itemCategory: String = targetId.toString(),
    /**
     * Unit fisik yang dipakai. WAJIB terisi untuk [serialized]: sejak V174 leg berserial
     * harus menunjuk aset, kalau tidak stok berkurang tanpa ada yang tahu SN mana yang keluar.
     */
    val assetId: UUID? = null,
    val serialNumber: String? = null,
)

data class InventoryFulfillmentResult(
    val tenantId: UUID,
    val operationKey: String,
    val targetId: UUID,
    val applied: Boolean,
    val replayed: Boolean,
    val recordedAt: Instant,
)
