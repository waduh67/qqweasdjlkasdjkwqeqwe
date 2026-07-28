package com.duluin.ftth.workorder.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.workorder.domain.model.WorkOrder
import com.duluin.ftth.workorder.domain.model.WorkOrderApprovalStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderEvent
import com.duluin.ftth.workorder.domain.model.WorkOrderStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import java.util.UUID

/** Port persistence untuk agregat [WorkOrder]. Query ter-scope tenant otomatis (Hibernate + RLS). */
interface WorkOrderRepository {

    /** Menyimpan agregat beserta event timeline yang tertunda, lalu mengosongkannya. */
    fun save(workOrder: WorkOrder): WorkOrder

    fun findById(id: UUID): WorkOrder?

    fun search(
        query: String?,
        type: WorkOrderType?,
        status: WorkOrderStatus?,
        assignedTo: UUID?,
        approvalStatus: WorkOrderApprovalStatus?,
        customerId: UUID?,
        pageRequest: PageRequest,
    ): Page<WorkOrder>

    /** Timeline sebuah work order, terlama lebih dulu. */
    fun timelineOf(workOrderId: UUID): List<WorkOrderEvent>

    /** Jumlah WO per status (status tanpa data absen dari peta). */
    fun countByStatus(): Map<WorkOrderStatus, Long>

    /** Jumlah WO per tipe (tipe tanpa data absen dari peta). */
    fun countByType(): Map<WorkOrderType, Long>

    /** Beban WO terbuka per teknisi; kunci `null` = kelompok yang belum ditugaskan. */
    fun countOpenByTechnician(): Map<UUID?, Long>

    /** Jumlah WO yang menunggu persetujuan (selesai tapi belum dikurasi). */
    fun countPendingApproval(): Long

    /**
     * Apakah pelanggan ini sudah punya WO preventif yang masih terbuka. Dasar
     * idempotensi pemeliharaan prediktif: satu pelanggan cukup satu kunjungan
     * preventif terjadwal, meski pemindaian berulang terus menandainya memburuk.
     */
    fun existsOpenPreventiveForCustomer(customerId: UUID): Boolean

    /** Semua WO ber-[type] yang masih terbuka (belum selesai/batal) — bahan pencocokan lintas-module. */
    fun findOpenByType(type: WorkOrderType): List<WorkOrder>

    fun deleteById(id: UUID)
}
