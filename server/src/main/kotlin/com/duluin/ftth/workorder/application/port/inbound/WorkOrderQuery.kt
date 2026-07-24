package com.duluin.ftth.workorder.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.workorder.domain.model.WorkOrderStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import java.time.Instant
import java.util.UUID

/** Membaca work order dan timeline-nya. */
interface WorkOrderQuery {

    fun search(filter: WorkOrderFilter, page: PageRequest): Page<WorkOrderView>

    /** Detail satu work order beserta timeline lengkapnya. */
    fun get(id: UUID): WorkOrderDetail
}

/** Penyaring daftar work order; semua bidang opsional. */
data class WorkOrderFilter(
    val query: String?,
    val type: WorkOrderType?,
    val status: WorkOrderStatus?,
    val assignedTo: UUID?,
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
    val incidentId: UUID?,
    val areaId: UUID?,
    val assignedTo: UUID?,
    /** Nama teknisi ter-assign, diresolusi lewat iam; `null` bila belum ditugaskan. */
    val assignedToName: String?,
    val scheduledAt: Instant?,
    val assignedAt: Instant?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val resolutionNote: String?,
    val cancelReason: String?,
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
