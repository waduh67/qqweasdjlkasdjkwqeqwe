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
import com.duluin.ftth.workorder.WorkorderApi
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
