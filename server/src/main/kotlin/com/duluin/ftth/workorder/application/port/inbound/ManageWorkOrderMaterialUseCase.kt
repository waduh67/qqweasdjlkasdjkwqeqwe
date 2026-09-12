package com.duluin.ftth.workorder.application.port.inbound

import com.duluin.ftth.inventory.IssuedMaterialLineInput
import com.duluin.ftth.inventory.PlannedMaterialLineInput
import com.duluin.ftth.inventory.WorkOrderMaterialTemplateView
import com.duluin.ftth.inventory.WorkOrderMaterialView
import com.duluin.ftth.inventory.WorkOrderRecoveredAssetView
import java.util.UUID

/**
 * Permukaan material work order dari sisi WO.
 *
 * Datanya dimiliki modul `inventory` — yang dikerjakan di sini hanya kontrol akses: WO ini
 * di area saya? ditugaskan ke saya? Tanpa lapisan ini, teknisi mana pun yang punya izin
 * `workorder.material.record` bisa mencatat pemakaian di WO orang lain, dan barang yang
 * hilang akan selalu bisa dilimpahkan ke WO yang tidak dia kerjakan.
 */
interface ManageWorkOrderMaterialUseCase {
    /** BOM untuk jenis WO ini — dipakai layar rencana untuk mempra-isi daftar material. */
    fun materialTemplate(workOrderId: UUID): List<WorkOrderMaterialTemplateView>

    fun materials(workOrderId: UUID): List<WorkOrderMaterialView>

    /** Ganti seluruh rencana. Daftar kosong berarti "pakai BOM jenis WO ini apa adanya". */
    fun planMaterial(workOrderId: UUID, lines: List<PlannedMaterialLineInput>): List<WorkOrderMaterialView>

    fun issueMaterial(workOrderId: UUID, request: WorkOrderMaterialIssueRequest): List<WorkOrderMaterialView>

    fun recordMaterialUsage(workOrderId: UUID, request: WorkOrderMaterialUsageRequest): WorkOrderMaterialView

    fun scanMaterialSerial(workOrderId: UUID, request: WorkOrderMaterialScanRequest): WorkOrderMaterialView

    // ------------------------------------------------- Penarikan aset (P2.6)

    /**
     * Scan unit yang DICABUT dari rumah pelanggan (lazimnya WO DISMANTLE).
     *
     * Kontrol aksesnya sama persis dengan [scanMaterialSerial] dan itu disengaja: yang men-scan
     * adalah orang yang sama, di WO yang sama, di hari yang sama. Membedakannya hanya akan
     * melahirkan WO yang teknisinya boleh mencatat pemakaian tapi tidak boleh mencatat barang
     * yang dia bawa pulang — dan barang itu akan tetap dibawa pulang, cuma tanpa jejak.
     */
    fun recoverAsset(workOrderId: UUID, request: WorkOrderAssetRecoveryRequest): WorkOrderRecoveredAssetView

    fun recoveredAssets(workOrderId: UUID): List<WorkOrderRecoveredAssetView>

    fun cancelRecoveredAsset(
        workOrderId: UUID,
        recoveredAssetId: UUID,
        request: WorkOrderAssetRecoveryCancelRequest,
    ): WorkOrderRecoveredAssetView
}

/**
 * [technicianId] + [technicianLocationId] WAJIB disebut klien, meniru [WorkOrderMaterialIssueRequest].
 *
 * Tidak disimpulkan dari WO: satu WO bisa ditugaskan ke lebih dari satu teknisi, dan menebak
 * "teknisi pertama" berarti saldo unit tarikan mendarat di van orang yang tidak membawanya.
 * Selisihnya baru ketahuan saat opname van, berbulan-bulan kemudian, di dua tempat sekaligus.
 */
data class WorkOrderAssetRecoveryRequest(
    val serialNumber: String,
    val technicianId: UUID,
    val technicianLocationId: UUID,
    /** GOOD / DAMAGED. */
    val condition: String = "GOOD",
    val note: String? = null,
)

data class WorkOrderAssetRecoveryCancelRequest(val reason: String? = null)

data class WorkOrderMaterialIssueRequest(
    val fromLocationId: UUID,
    val custodianId: UUID,
    val technicianId: UUID,
    /** Lokasi berjenis VEHICLE/TECHNICIAN yang mewakili van stock teknisi. */
    val technicianLocationId: UUID,
    val lines: List<IssuedMaterialLineInput>,
    val reason: String,
    val operationKey: String,
    val payloadHash: String,
)

data class WorkOrderMaterialUsageRequest(
    val itemId: UUID,
    val usedQuantity: Int,
    val returnedQuantity: Int = 0,
    val lostQuantity: Int = 0,
    val varianceReason: String? = null,
)

data class WorkOrderMaterialScanRequest(
    val serialNumber: String,
    /** INSTALLED / RETURNED / LOST. */
    val outcome: String,
    val macAddress: String? = null,
)
