package com.duluin.ftth.workorder.application.port.inbound

import com.duluin.ftth.workorder.domain.model.WorkOrderPriority
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import java.time.Instant
import java.util.UUID

/** Perubahan work order oleh operator/dispatcher: buat, ubah, tugaskan, jalankan lifecycle. */
interface ManageWorkOrderUseCase {

    fun create(command: SaveWorkOrderCommand): WorkOrderView

    fun update(id: UUID, command: UpdateWorkOrderCommand): WorkOrderView

    /** Menugaskan/menugaskan ulang ke seorang teknisi (harus pengguna aktif tenant ini). */
    fun assign(id: UUID, technicianId: UUID): WorkOrderView

    fun start(id: UUID): WorkOrderView

    fun complete(id: UUID, resolutionNote: String?): WorkOrderView

    fun cancel(id: UUID, reason: String?): WorkOrderView

    /** Merekam redaman optik (dBm) sebelum/sesudah pengerjaan sebagai bukti kualitas. */
    fun recordOptical(id: UUID, command: RecordOpticalCommand): WorkOrderView

    /** Penyelia menyetujui hasil kerja WO yang menunggu persetujuan. */
    fun approve(id: UUID, note: String?): WorkOrderView

    /** Penyelia menolak hasil kerja (alasan wajib); WO dibuka kembali untuk dikerjakan ulang. */
    fun reject(id: UUID, reason: String): WorkOrderView

    /** Menghapus work order yang masih DRAFT (belum pernah ditugaskan). */
    fun delete(id: UUID)
}

/** Membuat work order; `assignedTo` opsional (mengangkatnya langsung ke ASSIGNED). */
data class SaveWorkOrderCommand(
    val type: WorkOrderType,
    val title: String,
    val description: String?,
    val priority: WorkOrderPriority,
    val customerId: UUID?,
    val incidentId: UUID?,
    val areaId: UUID?,
    val scheduledAt: Instant?,
    val assignedTo: UUID?,
)

/** Mengubah rincian deskriptif; tipe tak bisa diubah, penugasan lewat [ManageWorkOrderUseCase.assign]. */
data class UpdateWorkOrderCommand(
    val title: String,
    val description: String?,
    val priority: WorkOrderPriority,
    val customerId: UUID?,
    val incidentId: UUID?,
    val areaId: UUID?,
    val scheduledAt: Instant?,
)

/** Redaman optik (dBm) yang diukur teknisi; keduanya opsional agar bisa direkam bertahap. */
data class RecordOpticalCommand(
    val rxBeforeDbm: Double?,
    val rxAfterDbm: Double?,
)
