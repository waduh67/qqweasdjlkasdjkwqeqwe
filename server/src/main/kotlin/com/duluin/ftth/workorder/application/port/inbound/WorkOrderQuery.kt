package com.duluin.ftth.workorder.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.workorder.domain.model.WorkOrderApprovalStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import java.time.Instant
import java.util.UUID

/** Membaca work order dan timeline-nya. */
interface WorkOrderQuery {

    fun search(filter: WorkOrderFilter, page: PageRequest): Page<WorkOrderView>

    /**
     * Papan tugas milik teknisi yang sedang login: hanya WO yang di-assign ke dirinya,
     * opsional disaring status. Sumber data untuk aplikasi teknisi mobile.
     */
    fun searchMine(status: WorkOrderStatus?, page: PageRequest): Page<WorkOrderView>

    /** Detail satu work order beserta timeline lengkapnya. */
    fun get(id: UUID): WorkOrderDetail

    /** Ringkasan dispatch: sebaran status/tipe, antrean belum ditugaskan, dan beban tiap teknisi. */
    fun dashboard(): WorkOrderDashboardView
}

/** Penyaring daftar work order; semua bidang opsional. */
data class WorkOrderFilter(
    val query: String?,
    val type: WorkOrderType?,
    val status: WorkOrderStatus?,
    val assignedTo: UUID?,
    /** Antrean persetujuan: mis. PENDING = hasil kerja yang menunggu dikurasi. */
    val approvalStatus: WorkOrderApprovalStatus?,
    /** Batasi ke WO milik satu pelanggan (riwayat pekerjaan di panel Subscriber-360). */
    val customerId: UUID? = null,
)

data class WorkOrderView(
    val id: UUID,
    val code: String,
    val type: String,
    val status: String,
    val priority: String,
    val title: String,
    val description: String?,
    val customerId: UUID?,
    /** Nama pelanggan tertaut, diresolusi lewat customer; `null` bila tak tertaut/tak ada. */
    val customerName: String?,
    /** Langganan yang WO ini kerjakan (PSB/DISMANTLE); `null` bila WO tak tertaut langganan. */
    val subscriptionId: UUID?,
    val incidentId: UUID?,
    val areaId: UUID?,
    /** Koordinat lokasi pelanggan tertaut (untuk navigasi teknisi lapangan); `null` bila WO tak tertaut pelanggan. */
    val destinationLat: Double?,
    val destinationLng: Double?,
    val assignedTo: UUID?,
    /** Nama teknisi ter-assign, diresolusi lewat iam; `null` bila belum ditugaskan. */
    val assignedToName: String?,
    val scheduledAt: Instant?,
    val assignedAt: Instant?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val resolutionNote: String?,
    val cancelReason: String?,
    /** Redaman optik (dBm) yang diukur teknisi sebelum & sesudah pengerjaan; `null` bila belum direkam. */
    val rxBeforeDbm: Double?,
    val rxAfterDbm: Double?,
    /** Kurasi hasil kerja: PENDING/APPROVED/REJECTED; `null` bila WO belum pernah selesai. */
    val approvalStatus: String?,
    val approvedBy: UUID?,
    /** Nama pengambil keputusan persetujuan, diresolusi lewat iam; `null` bila belum ada/tak ada. */
    val approvedByName: String?,
    val approvedAt: Instant?,
    val approvalNote: String?,
    val createdAt: Instant,
)

data class WorkOrderDetail(
    val workOrder: WorkOrderView,
    val timeline: List<WorkOrderEventView>,
)

data class WorkOrderEventView(
    val type: String,
    val message: String,
    val at: Instant,
)

/**
 * Ringkasan untuk papan dispatch. `byStatus`/`byType` selalu memuat semua nilai enum
 * (yang tanpa data = 0) agar tampilan stabil; `workloads` hanya teknisi yang sedang
 * memegang WO terbuka, terbanyak lebih dulu.
 */
data class WorkOrderDashboardView(
    val total: Long,
    val open: Long,
    val unassignedOpen: Long,
    val pendingApproval: Long,
    val byStatus: Map<String, Long>,
    val byType: Map<String, Long>,
    val workloads: List<TechnicianWorkloadView>,
)

data class TechnicianWorkloadView(
    val technicianId: UUID,
    /** Nama teknisi, diresolusi lewat iam; `null` bila pengguna sudah tak ada. */
    val technicianName: String?,
    val openCount: Long,
)
