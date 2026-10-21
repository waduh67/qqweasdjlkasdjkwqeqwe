package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.domain.error.ConflictException
import com.karuhun.ftth.common.domain.error.AccessDeniedException
import com.karuhun.ftth.fieldservice.application.port.inbound.CreateVisitCommand
import com.karuhun.ftth.fieldservice.application.port.inbound.FieldServiceUseCase
import com.karuhun.ftth.fieldservice.application.port.outbound.CommandOutcomeStore
import com.karuhun.ftth.fieldservice.application.port.outbound.VisitRepository
import com.karuhun.ftth.fieldservice.domain.model.AttendanceDecision
import com.karuhun.ftth.fieldservice.domain.model.CommandMetadata
import com.karuhun.ftth.fieldservice.domain.model.Visit
import com.karuhun.ftth.fieldservice.domain.model.VisitState
import com.karuhun.ftth.fieldservice.domain.model.VisitPurpose
import com.karuhun.ftth.iam.UserRef
import com.karuhun.ftth.common.security.AuthenticatedUser
import com.karuhun.ftth.workorder.WorkorderApi
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import com.karuhun.ftth.common.domain.Page
import com.karuhun.ftth.common.domain.PageRequest

@Transactional
class FieldServiceService(
    private val visits: VisitRepository,
    private val outcomes: CommandOutcomeStore,
    private val workorders: WorkorderApi,
    private val actorLookup: (UUID) -> UserRef?,
) : FieldServiceUseCase {
    fun visitForHttp(tenantId: UUID, visitId: UUID): Visit? = visits.findById(tenantId, visitId)
    fun workSessionForHttp(tenantId: UUID, visitId: UUID) = visits.findWorkSession(tenantId, visitId)

    @Transactional(readOnly = true)
    fun listForHttp(actor: AuthenticatedUser, scope: VisitListScope, status: VisitState?, page: PageRequest,
        history: VisitHistory = VisitHistory.ALL): Page<FieldServiceVisitView> {
        val candidates = when (scope) {
            VisitListScope.SELF -> {
                if (!actor.hasPermission("workorder.order.field")) throw com.karuhun.ftth.common.domain.error.AccessDeniedException("Technician visit scope is required")
                visits.findAllByTechnician(actor.tenantId, actor.userId)
            }
            VisitListScope.AREA, VisitListScope.ALL -> {
                if (!actor.hasPermission("fieldservice.visit.view") && !actor.hasPermission("workorder.order.view")) {
                    throw com.karuhun.ftth.common.domain.error.AccessDeniedException("Dispatcher visit scope is required")
                }
                visits.findAll(actor.tenantId)
            }
        }.asSequence()
            .filter { status == null || it.state == status }
            .mapNotNull { visit ->
                val context = workorders.visitContext(visit.workOrderId)
                val assignment = if (context == null) workorders.assignment(visit.workOrderId, visit.technicianId) else null
                if (context == null && assignment == null) return@mapNotNull null
                val area = context?.areaId ?: assignment?.areaId
                val areaAllowed = scope == VisitListScope.SELF || !actor.areaRestricted || area in actor.areaIds
                if (!areaAllowed) return@mapNotNull null
                val active = visit.assignmentActive && visit.state !in setOf(VisitState.SUBMITTED, VisitState.CANCELLED) &&
                    (context?.let { it.active && visit.technicianId in it.technicianIds &&
                        (visit.purpose != VisitPurpose.WORK_ORDER || visit.assignmentGeneration == it.assignmentGeneration) } ?: (assignment?.active == true))
                if (history == VisitHistory.ACTIVE && !active || history == VisitHistory.HISTORY && active) return@mapNotNull null
                FieldServiceVisitView(
                    id = visit.id,
                    workOrderId = visit.workOrderId,
                    orderId = visit.orderId,
                    state = visit.state,
                    revision = visit.revision,
                    scheduledAt = context?.scheduledAt ?: workorders.scheduledAt(visit.workOrderId),
                    session = WorkSessionSummary(null, null, null),
                    workOrderCode = context?.code ?: "WO-${visit.workOrderId.toString().takeLast(8).uppercase()}",
                    title = context?.title ?: "Kunjungan pekerjaan",
                    customerName = context?.customerName, areaName = context?.areaName, purpose = visit.purpose,
                    actionable = active && visit.technicianId == actor.userId && actor.hasPermission("workorder.order.field"),
                )
            }
            .toList()
        val from = page.page * page.size
        val content = candidates.drop(from).take(page.size).map { view ->
            val session = visits.findWorkSession(actor.tenantId, view.id)
            view.copy(session = WorkSessionSummary(session?.startedAt, session?.endedAt, session?.submittedAt))
        }
        return Page(content, page.page, page.size, candidates.size.toLong())
    }

    @Transactional(readOnly = true)
    fun readableForHttp(actor: AuthenticatedUser, visitId: UUID): Visit? {
        val visit = visits.findById(actor.tenantId, visitId) ?: return null
        if (visit.technicianId == actor.userId && actor.hasPermission("workorder.order.field")) return visit
        val context = workorders.visitContext(visit.workOrderId)
        val area = context?.areaId ?: workorders.assignment(visit.workOrderId, visit.technicianId)?.areaId
        val areaAllowed = !actor.areaRestricted || area in actor.areaIds
        val privileged = actor.hasPermission("fieldservice.visit.view") || actor.hasPermission("fieldservice.visit.manage")
        return visit.takeIf { areaAllowed && privileged }
    }
    override fun create(command: CreateVisitCommand): Visit {
        val assignment = workorders.assignment(command.workOrderId, command.technicianId)
            ?: throw ConflictException("Work order assignment is not active")
        if (assignment.tenantId != command.tenantId || assignment.orderId != command.orderId || !assignment.active) {
            throw ConflictException("Order/work order assignment is invalid")
        }
        val technician = actorLookup(command.technicianId)
        if (technician?.active != true || technician.technician != true) throw ConflictException("Technician is inactive or ineligible")
        if (!command.operation.supervisor && command.operation.actorId != command.technicianId) throw AccessDeniedException("Only assigned technician may create a visit")
        outcomes.record(command.operation, "visit-created")
        return visits.save(Visit.plan(command.tenantId, command.orderId, command.workOrderId, command.technicianId, command.plannedAt))
    }

    override fun checkIn(visitId: UUID, command: CommandMetadata, receivedAt: Instant, decision: AttendanceDecision, reason: String?): Visit = synchronizedMutate(visitId, command) { it.checkIn(command, receivedAt, decision, reason) }
    override fun onSite(visitId: UUID, command: CommandMetadata, receivedAt: Instant): Visit = synchronizedMutate(visitId, command) { it.onSite(command, receivedAt) }
    override fun checkOut(visitId: UUID, command: CommandMetadata, receivedAt: Instant): Visit = synchronizedMutate(visitId, command) { it.checkOut(command, receivedAt) }
    override fun submit(visitId: UUID, command: CommandMetadata, receivedAt: Instant): Visit = synchronizedMutate(visitId, command) { it.submit(command, receivedAt) }

    private fun synchronizedMutate(visitId: UUID, command: CommandMetadata, action: (Visit) -> Unit): Visit = synchronized(this) {
        val initial = visits.findById(command.tenantId, visitId) ?: throw ConflictException("Visit is not available in this tenant")
        val context = workorders.visitContext(initial.workOrderId, lock = true)
        val visit = visits.findByIdForUpdate(command.tenantId, visitId) ?: throw ConflictException("Visit is not available in this tenant")
        val actor = actorLookup(command.actorId)
        if (command.actorId != visit.technicianId || actor?.active != true || !actor.technician)
            throw AccessDeniedException("Only the active assigned technician may change this visit")
        val assignment = if (context == null) workorders.assignment(visit.workOrderId, visit.technicianId) else null
        val current = context?.let { it.tenantId == command.tenantId && it.active && visit.technicianId in it.technicianIds &&
            (visit.purpose != VisitPurpose.WORK_ORDER || visit.assignmentGeneration == it.assignmentGeneration) }
            ?: (assignment?.tenantId == command.tenantId && assignment.active)
        if (!visit.assignmentActive || !current) throw ConflictException("Work order assignment is stale or closed")
        outcomes.find(command)?.let {
            if (it.payloadHash != command.payloadHash || it.targetId != visit.id)
                throw ConflictException("Operation key was used with a different payload or visit")
            return@synchronized visit
        }
        action(visit)
        outcomes.record(command, visit.id, visit.state.name)
        return visits.save(visit)
    }
}

enum class VisitListScope { SELF, AREA, ALL }
enum class VisitHistory { ALL, ACTIVE, HISTORY }

data class WorkSessionSummary(
    val startedAt: Instant?,
    val endedAt: Instant?,
    val submittedAt: Instant?,
)

data class FieldServiceVisitView(
    val id: UUID,
    val workOrderId: UUID,
    val orderId: UUID?,
    val state: VisitState,
    val revision: Long,
    val scheduledAt: Instant?,
    val session: WorkSessionSummary,
    val workOrderCode: String,
    val title: String,
    val customerName: String?,
    val areaName: String?,
    val purpose: VisitPurpose,
    val actionable: Boolean,
)
