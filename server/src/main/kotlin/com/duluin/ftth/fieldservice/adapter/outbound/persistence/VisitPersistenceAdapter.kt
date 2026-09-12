package com.duluin.ftth.fieldservice.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.fieldservice.VisitCancellationCause
import com.duluin.ftth.fieldservice.application.port.outbound.VisitRepository
import com.duluin.ftth.fieldservice.domain.model.Attendance
import com.duluin.ftth.fieldservice.domain.model.VisitCancellation
import com.duluin.ftth.fieldservice.domain.model.AttendanceDecision
import com.duluin.ftth.fieldservice.domain.model.Visit
import com.duluin.ftth.fieldservice.domain.model.VisitState
import com.duluin.ftth.fieldservice.domain.model.WorkSession
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "fieldservice_visit")
class VisitJpaEntity(
    id: UUID,
    @Column(name = "order_id", nullable = false, updatable = false) var orderId: UUID,
    @Column(name = "work_order_id", nullable = false, updatable = false) var workOrderId: UUID,
    @Column(name = "technician_id", nullable = false, updatable = false) var technicianId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var state: VisitState,
    @Column(nullable = false) var revision: Long,
    @Column(name = "assignment_active", nullable = false) var assignmentActive: Boolean,
    @Enumerated(EnumType.STRING) @Column(name = "attendance_decision") var attendanceDecision: AttendanceDecision?,
    @Column(name = "attendance_reason") var attendanceReason: String?,
    @Column(name = "attendance_received_at") var attendanceReceivedAt: Instant?,
    // V191. `cause` dan `cancelledAt` WAJIB berpasangan (ck_fieldservice_visit_cancellation_pair):
    // menyalin salah satunya saja meledak sebagai DataIntegrityViolationException saat flush,
    // jauh dari baris yang salah.
    @Enumerated(EnumType.STRING) @Column(name = "cancellation_cause", length = 40) var cancellationCause: VisitCancellationCause? = null,
    @Column(name = "cancellation_reason", length = 500) var cancellationReason: String? = null,
    @Column(name = "cancelled_at") var cancelledAt: Instant? = null,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "fieldservice_work_session")
class WorkSessionJpaEntity(
    id: UUID,
    @Column(name = "visit_id", nullable = false, updatable = false) var visitId: UUID,
    @Column(name = "work_order_id", nullable = false, updatable = false) var workOrderId: UUID,
    @Column(name = "technician_id", nullable = false, updatable = false) var technicianId: UUID,
    @Column(name = "started_at") var startedAt: Instant?,
    @Column(name = "ended_at") var endedAt: Instant?,
    @Column(name = "submitted_at") var submittedAt: Instant?,
) : TenantAwareJpaEntity(id)

interface VisitJpaRepository : JpaRepository<VisitJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByTenantIdAndId(tenantId: UUID, id: UUID): VisitJpaEntity?
    fun findByTenantIdAndWorkOrderId(tenantId: UUID, workOrderId: UUID): List<VisitJpaEntity>
    fun findByTenantIdOrderByCreatedAtDescIdDesc(tenantId: UUID): List<VisitJpaEntity>
    fun findByTenantIdAndTechnicianIdOrderByCreatedAtDescIdDesc(tenantId: UUID, technicianId: UUID): List<VisitJpaEntity>
}

interface WorkSessionJpaRepository : JpaRepository<WorkSessionJpaEntity, UUID> {
    fun findByTenantIdAndVisitId(tenantId: UUID, visitId: UUID): WorkSessionJpaEntity?
}

@Component
class VisitPersistenceAdapter(private val visits: VisitJpaRepository, private val sessions: WorkSessionJpaRepository) : VisitRepository {
    override fun save(visit: Visit): Visit {
        val entity = visits.findByTenantIdAndId(visit.tenantId, visit.id)?.apply {
            state = visit.state; revision = visit.revision; assignmentActive = visit.assignmentActive
            attendanceDecision = visit.attendance?.decision; attendanceReason = visit.attendance?.reason; attendanceReceivedAt = visit.attendance?.serverReceivedAt
            cancellationCause = visit.cancellation?.cause; cancellationReason = visit.cancellation?.reason; cancelledAt = visit.cancellation?.cancelledAt
        } ?: VisitJpaEntity(visit.id, visit.orderId, visit.workOrderId, visit.technicianId, visit.state, visit.revision, visit.assignmentActive, visit.attendance?.decision, visit.attendance?.reason, visit.attendance?.serverReceivedAt,
            visit.cancellation?.cause, visit.cancellation?.reason, visit.cancellation?.cancelledAt)
        visits.save(entity)
        val session = sessions.findByTenantIdAndVisitId(visit.tenantId, visit.id)
            ?: WorkSessionJpaEntity(UUID.randomUUID(), visit.id, visit.workOrderId, visit.technicianId, null, null, null)
        if (visit.state == VisitState.CHECKED_IN && session.startedAt == null) session.startedAt = Instant.now()
        if (visit.state == VisitState.CHECKED_OUT && session.endedAt == null) session.endedAt = Instant.now()
        if (visit.state == VisitState.SUBMITTED) session.submittedAt = Instant.now()
        sessions.save(session)
        return visit
    }

    override fun findById(tenantId: UUID, visitId: UUID): Visit? = visits.findByTenantIdAndId(tenantId, visitId)?.toDomain()

    override fun findAll(tenantId: UUID): List<Visit> = visits.findByTenantIdOrderByCreatedAtDescIdDesc(tenantId).map { it.toDomain() }

    override fun findAllByTechnician(tenantId: UUID, technicianId: UUID): List<Visit> =
        visits.findByTenantIdAndTechnicianIdOrderByCreatedAtDescIdDesc(tenantId, technicianId).map { it.toDomain() }

    override fun findByWorkOrderId(tenantId: UUID, workOrderId: UUID): List<Visit> =
        visits.findByTenantIdAndWorkOrderId(tenantId, workOrderId).map { it.toDomain() }

    override fun findWorkSession(tenantId: UUID, visitId: UUID): WorkSession? = sessions.findByTenantIdAndVisitId(tenantId, visitId)?.let {
        WorkSession(it.id, it.tenantId!!, it.visitId, it.workOrderId, it.technicianId, it.startedAt, it.endedAt, it.submittedAt)
    }

    private fun VisitJpaEntity.toDomain() = Visit.rehydrate(id, tenantId!!, orderId, workOrderId, technicianId, state, revision, assignmentActive,
        attendanceDecision?.let { Attendance(it, attendanceReason, attendanceReceivedAt ?: Instant.EPOCH) },
        cancellationCause?.let { VisitCancellation(it, cancellationReason.orEmpty(), cancelledAt ?: Instant.EPOCH) })
}
