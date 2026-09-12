package com.duluin.ftth.fieldservice.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.fieldservice.application.port.inbound.CreateVisitCommand
import com.duluin.ftth.fieldservice.application.port.inbound.FieldServiceUseCase
import com.duluin.ftth.fieldservice.application.port.outbound.CommandOutcomeStore
import com.duluin.ftth.fieldservice.application.port.outbound.VisitRepository
import com.duluin.ftth.fieldservice.domain.model.AttendanceDecision
import com.duluin.ftth.fieldservice.domain.model.CommandMetadata
import com.duluin.ftth.fieldservice.domain.model.Visit
import com.duluin.ftth.fieldservice.domain.model.VisitState
import com.duluin.ftth.iam.UserRef
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.fieldservice.VisitCancellationCause
import com.duluin.ftth.fieldservice.VisitCancelled
import com.duluin.ftth.workorder.WorkorderApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest

@Transactional
class FieldServiceService(
    private val visits: VisitRepository,
    private val outcomes: CommandOutcomeStore,
    private val workorders: WorkorderApi,
    private val actorLookup: (UUID) -> UserRef?,
    private val events: ApplicationEventPublisher,
) : FieldServiceUseCase {
    fun visitForHttp(tenantId: UUID, visitId: UUID): Visit? = visits.findById(tenantId, visitId)
    fun workSessionForHttp(tenantId: UUID, visitId: UUID) = visits.findWorkSession(tenantId, visitId)

    fun listForHttp(actor: AuthenticatedUser, scope: VisitListScope, status: VisitState?, page: PageRequest): Page<FieldServiceVisitView> {
        val candidates = when (scope) {
            VisitListScope.SELF -> {
                if (!actor.hasPermission("workorder.order.field")) throw com.duluin.ftth.common.domain.error.AccessDeniedException("Technician visit scope is required")
                visits.findAllByTechnician(actor.tenantId, actor.userId)
            }
            VisitListScope.AREA, VisitListScope.ALL -> {
                if (!actor.hasPermission("fieldservice.visit.view") && !actor.hasPermission("workorder.order.view")) {
                    throw com.duluin.ftth.common.domain.error.AccessDeniedException("Dispatcher visit scope is required")
                }
                visits.findAll(actor.tenantId)
            }
        }.asSequence()
            .filter { status == null || it.state == status }
            .mapNotNull { visit ->
                val assignment = workorders.assignment(visit.workOrderId, visit.technicianId) ?: return@mapNotNull null
                val areaAllowed = !actor.areaRestricted || (assignment.areaId != null && assignment.areaId in actor.areaIds)
                if (!areaAllowed) return@mapNotNull null
                val session = visits.findWorkSession(actor.tenantId, visit.id)
                FieldServiceVisitView(
                    id = visit.id,
                    workOrderId = visit.workOrderId,
                    orderId = visit.orderId,
                    state = visit.state,
                    revision = visit.revision,
                    scheduledAt = workorders.scheduledAt(visit.workOrderId),
                    session = WorkSessionSummary(session?.startedAt, session?.endedAt, session?.submittedAt),
                )
            }
            .toList()
        val from = page.page * page.size
        val content = candidates.drop(from).take(page.size)
        return Page(content, page.page, page.size, candidates.size.toLong())
    }

    fun readableForHttp(actor: AuthenticatedUser, visitId: UUID): Visit? {
        val visit = visits.findById(actor.tenantId, visitId) ?: return null
        val assignment = workorders.assignment(visit.workOrderId, visit.technicianId) ?: return null
        val areaAllowed = !actor.areaRestricted || (assignment.areaId != null && assignment.areaId in actor.areaIds)
        val privileged = actor.hasPermission("fieldservice.visit.view") || actor.hasPermission("fieldservice.visit.manage")
        return visit.takeIf { areaAllowed && (privileged || visit.technicianId == actor.userId) }
    }
    override fun create(command: CreateVisitCommand): Visit {
        val assignment = workorders.assignment(command.workOrderId, command.technicianId)
            ?: throw ConflictException("Work order assignment is not active")
        if (assignment.tenantId != command.tenantId || assignment.orderId != command.orderId || !assignment.active) {
            throw ConflictException("Order/work order assignment is invalid")
        }
        val technician = actorLookup(command.technicianId)
        if (technician?.active != true || technician.technician != true) throw ConflictException("Technician is inactive or ineligible")
        outcomes.record(command.operation, "visit-created")
        return visits.save(Visit.plan(command.tenantId, command.orderId, command.workOrderId, command.technicianId, command.plannedAt))
    }

    override fun checkIn(visitId: UUID, command: CommandMetadata, receivedAt: Instant, decision: AttendanceDecision, reason: String?): Visit = synchronizedMutate(visitId, command) { it.checkIn(command, receivedAt, decision, reason) }
    override fun onSite(visitId: UUID, command: CommandMetadata, receivedAt: Instant): Visit = synchronizedMutate(visitId, command) { it.onSite(command, receivedAt) }
    override fun checkOut(visitId: UUID, command: CommandMetadata, receivedAt: Instant): Visit = synchronizedMutate(visitId, command) { it.checkOut(command, receivedAt) }
    override fun submit(visitId: UUID, command: CommandMetadata, receivedAt: Instant): Visit = synchronizedMutate(visitId, command) { it.submit(command, receivedAt) }

    /**
     * Pembatalan kunjungan DENGAN sebab. Tidak lewat [synchronizedMutate] karena di sini kita
     * harus tahu apakah mutasinya benar-benar terjadi atau cuma replay operation key: menerbitkan
     * [VisitCancelled] pada replay berarti pesanan ditandai ulang di portal setiap kali teknisi
     * yang sinyalnya buruk mengirim ulang permintaan yang sama.
     */
    override fun cancel(visitId: UUID, command: CommandMetadata, cause: VisitCancellationCause, reason: String, receivedAt: Instant): Visit =
        synchronized(this) {
            val visit = visits.findById(command.tenantId, visitId) ?: throw ConflictException("Visit is not available in this tenant")
            if (outcomes.find(command) != null) return@synchronized visit
            visit.cancel(command, reason, cause, receivedAt)
            outcomes.record(command, visit.state.name)
            val saved = visits.save(visit)
            /*
             * `Visit.orderId` SENGAJA TIDAK dipakai di sini. Namanya berbohong: `create`
             * mencocokkannya dengan `WorkOrderAssignmentRef.orderId`, yang diisi `customerId`
             * work order-nya, sehingga setiap kunjungan yang lolos dibuat menyimpan id PELANGGAN
             * di kolom bernama `order_id`. Menerbitkannya apa adanya membuat module order mencari
             * pesanan memakai id pelanggan — nol baris, tanpa error, dan pemicunya tampak "sudah
             * jalan" padahal tak pernah menandai apa pun. Id pesanan yang benar hanya dipegang
             * work order-nya.
             */
            val orderId = workorders.orderIdOf(visit.workOrderId)
            /*
             * Diterbitkan di DALAM transaksi, dan pendengarnya (module order) memakai
             * BEFORE_COMMIT: penanda portal dan pembatalan kunjungan harus jadi satu fakta.
             * Kalau penandanya ditulis setelah commit dan gagal, kunjungannya tercatat gagal
             * tapi portal pelanggan tetap berkata "sedang dijadwalkan" — persis keadaan yang
             * fitur ini dibuat untuk menghapusnya, hanya kini lebih sulit dilacak.
             *
             * WO tanpa pesanan (REPAIR dari helpdesk, DISMANTLE) tidak menerbitkan apa pun:
             * tidak ada pesanan untuk ditandai, dan event ber-`orderId` palsu hanya memindahkan
             * kebingungannya ke pendengar.
             */
            if (orderId != null) {
                events.publishEvent(
                    VisitCancelled(visit.tenantId, visit.id, orderId, visit.workOrderId, visit.technicianId, cause, reason, receivedAt),
                )
            }
            return saved
        }

    private fun synchronizedMutate(visitId: UUID, command: CommandMetadata, action: (Visit) -> Unit): Visit = synchronized(this) {
        val visit = visits.findById(command.tenantId, visitId) ?: throw ConflictException("Visit is not available in this tenant")
        if (outcomes.find(command) != null) return@synchronized visit
        action(visit)
        outcomes.record(command, visit.state.name)
        return visits.save(visit)
    }
}

enum class VisitListScope { SELF, AREA, ALL }

data class WorkSessionSummary(
    val startedAt: Instant?,
    val endedAt: Instant?,
    val submittedAt: Instant?,
)

data class FieldServiceVisitView(
    val id: UUID,
    val workOrderId: UUID,
    val orderId: UUID,
    val state: VisitState,
    val revision: Long,
    val scheduledAt: Instant?,
    val session: WorkSessionSummary,
)
