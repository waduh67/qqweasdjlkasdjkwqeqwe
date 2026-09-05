package com.duluin.ftth.fieldservice

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.fieldservice.domain.model.AttendanceDecision
import com.duluin.ftth.fieldservice.domain.model.CommandMetadata
import com.duluin.ftth.fieldservice.domain.model.Visit
import com.duluin.ftth.fieldservice.domain.model.VisitState
import com.duluin.ftth.fieldservice.application.port.outbound.InMemoryCommandOutcomeStore
import com.duluin.ftth.fieldservice.application.port.outbound.VisitRepository
import com.duluin.ftth.fieldservice.application.port.inbound.CreateVisitCommand
import com.duluin.ftth.fieldservice.application.service.FieldServiceService
import com.duluin.ftth.fieldservice.application.service.VisitListScope
import com.duluin.ftth.iam.UserRef
import com.duluin.ftth.workorder.WorkOrderAssignmentRef
import com.duluin.ftth.workorder.WorkorderApi
import com.duluin.ftth.workorder.WorkOrderRef
import com.duluin.ftth.workorder.RaisePsbCommand
import com.duluin.ftth.workorder.RaiseRepairCommand
import com.duluin.ftth.workorder.FieldOpsReport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class FieldServiceDomainTest {
    private val tenant = UUID.randomUUID()
    private val order = UUID.randomUUID()
    private val workOrder = UUID.randomUUID()
    private val technician = UUID.randomUUID()
    private val supervisor = UUID.randomUUID()
    private val metadata = CommandMetadata(tenant, technician, "visit.check-in", "one", "hash", 0)

    private fun visit() = Visit.plan(tenant, order, workOrder, technician, Instant.parse("2026-09-04T10:00:00Z"))

    @Test
    fun `happy path emits ordered public events`() {
        val visit = visit()
        visit.checkIn(metadata, Instant.parse("2026-09-04T10:01:00Z"), AttendanceDecision.ACCEPTED, "customer confirmed")
        visit.onSite(metadata.copy(namespace = "visit.on-site", operationKey = "two", revision = 1), Instant.parse("2026-09-04T10:02:00Z"))
        visit.checkOut(metadata.copy(namespace = "visit.check-out", operationKey = "three", revision = 2), Instant.parse("2026-09-04T10:30:00Z"))
        visit.submit(metadata.copy(namespace = "visit.submit", operationKey = "four", revision = 3), Instant.parse("2026-09-04T10:31:00Z"))

        assertThat(visit.state).isEqualTo(VisitState.SUBMITTED)
        assertThat(visit.events.map { it.type }).containsExactly("VisitCheckedIn", "VisitOnSite", "VisitCheckedOut", "VisitSubmitted")
        assertThat(visit.attendance?.reason).isEqualTo("customer confirmed")
    }

    @Test
    fun `stale revision and revoked assignment become conflict`() {
        val visit = visit()
        assertThatThrownBy { visit.checkIn(metadata.copy(revision = 2), Instant.now(), AttendanceDecision.ACCEPTED, null) }
            .isInstanceOf(ConflictException::class.java)
        visit.revokeAssignment()
        assertThatThrownBy { visit.checkIn(metadata, Instant.now(), AttendanceDecision.ACCEPTED, null) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(visit.state).isEqualTo(VisitState.CONFLICT)
    }

    @Test
    fun `only supervisor can cancel conflict and cancellation is terminal`() {
        val visit = visit()
        visit.revokeAssignment()
        assertThatThrownBy { visit.cancel(metadata.copy(actorId = technician), "stale") }
            .isInstanceOf(AccessDeniedException::class.java)
        visit.cancel(metadata.copy(actorId = supervisor, supervisor = true), "assignment revoked")
        assertThat(visit.state).isEqualTo(VisitState.CANCELLED)
        assertThatThrownBy { visit.rebaseAndSubmit(metadata.copy(actorId = supervisor)) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `same operation replays while different hash conflicts`() {
        val visit = visit()
        val store = InMemoryCommandOutcomeStore()
        val outcome = store.record(metadata, "accepted")
        assertThat(store.record(metadata, "ignored")).isEqualTo(outcome)
        assertThatThrownBy { store.record(metadata.copy(payloadHash = "different"), "other") }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `create rejects inactive technician and invalid assignment`() {
        val repository = object : VisitRepository {
            private var value: Visit? = null
            override fun save(visit: Visit): Visit { value = visit; return visit }
            override fun findById(tenantId: UUID, visitId: UUID): Visit? = value
        }
        val workorders = object : WorkorderApi {
            override fun assignment(workOrderId: UUID, technicianId: UUID) = WorkOrderAssignmentRef(tenant, workOrderId, order, technicianId, true, null)
            override fun openPsbByCustomer(): Map<UUID, WorkOrderRef> = error("unused")
            override fun raisePsb(command: RaisePsbCommand): WorkOrderRef = error("unused")
            override fun raiseRepair(command: RaiseRepairCommand): WorkOrderRef = error("unused")
            override fun fieldOpsReport(from: LocalDate, to: LocalDate): FieldOpsReport = error("unused")
        }
        val service = FieldServiceService(repository, InMemoryCommandOutcomeStore(), workorders) { UserRef(technician, "Revoked", "x", false, true) }
        val create = CreateVisitCommand(tenant, order, workOrder, technician, Instant.now(), metadata.copy(namespace = "visit.create"))
        assertThatThrownBy { service.create(create) }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `visit list self scope is assigned tenant safe and paginated`() {
        val assigned = visit()
        val other = Visit.plan(tenant, order, workOrder, supervisor, Instant.now())
        val repository = object : VisitRepository {
            override fun save(visit: Visit): Visit = visit
            override fun findById(tenantId: UUID, visitId: UUID): Visit? = listOf(assigned, other).firstOrNull { it.id == visitId }
            override fun findAllByTechnician(tenantId: UUID, technicianId: UUID): List<Visit> =
                listOf(assigned).filter { it.technicianId == technicianId }
        }
        val workorders = object : WorkorderApi {
            override fun assignment(workOrderId: UUID, technicianId: UUID) = WorkOrderAssignmentRef(tenant, workOrderId, order, technicianId, true, null)
            override fun scheduledAt(workOrderId: UUID) = Instant.parse("2026-09-04T10:00:00Z")
            override fun openPsbByCustomer(): Map<UUID, WorkOrderRef> = error("unused")
            override fun raisePsb(command: RaisePsbCommand): WorkOrderRef = error("unused")
            override fun raiseRepair(command: RaiseRepairCommand): WorkOrderRef = error("unused")
            override fun fieldOpsReport(from: LocalDate, to: LocalDate): FieldOpsReport = error("unused")
        }
        val service = FieldServiceService(repository, InMemoryCommandOutcomeStore(), workorders) { UserRef(technician, "Tech", "x", true, true) }
        val actor = AuthenticatedUser(technician, tenant, "tech@example.test", "Tech", false, setOf("workorder.order.field"), emptySet())

        val result = service.listForHttp(actor, VisitListScope.SELF, null, PageRequest(page = 0, size = 1))

        assertThat(result.content).hasSize(1)
        assertThat(result.content.single().id).isEqualTo(assigned.id)
        assertThat(result.content.single().scheduledAt).isEqualTo(Instant.parse("2026-09-04T10:00:00Z"))
        assertThat(result.totalElements).isEqualTo(1)
    }
}
