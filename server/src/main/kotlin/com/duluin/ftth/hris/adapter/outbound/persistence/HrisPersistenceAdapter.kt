package com.duluin.ftth.hris.adapter.outbound.persistence

import com.duluin.ftth.hris.application.port.HrisAttendanceRepository
import com.duluin.ftth.hris.domain.AttendanceSession
import com.duluin.ftth.hris.domain.AttendanceCorrection
import com.duluin.ftth.hris.domain.CorrectionState
import com.duluin.ftth.hris.application.port.*
import tools.jackson.databind.ObjectMapper
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import com.duluin.ftth.common.security.CurrentUserProvider
import java.time.LocalDate
import java.util.UUID
import com.duluin.ftth.hris.application.port.HrisPolicyRepository
import com.duluin.ftth.hris.application.port.HrisPolicySnapshot
import com.duluin.ftth.hris.domain.*

@Component
class HrisPersistenceAdapter(private val jpa: HrisAttendanceSessionJpaRepository, private val revisions: HrisAttendanceRevisionJpaRepository) : HrisAttendanceRepository {
    override fun save(session: AttendanceSession): AttendanceSession {
        val entity = jpa.findById(session.id).orElseGet { HrisAttendanceSessionJpaEntity(session.id) }
        entity.employeeId = session.employeeId
        entity.workDate = session.workDate
        entity.checkInAt = session.checkInAt
        entity.checkOutAt = session.checkOutAt
        entity.decision = session.decision.name
        entity.gpsEvidence = session.gpsEvidence
        jpa.save(entity)
        revisions.saveAll(session.revisions.filter { !revisions.existsById(it.id) }.map { revision -> HrisAttendanceRevisionJpaEntity(revision.id).apply { sessionId = revision.sessionId; this.revision = revision.revision; checkInAt = revision.checkInAt; checkOutAt = revision.checkOutAt; correctionId = revision.correctionId; approvedAt = revision.approvedAt } })
        return session
    }
    override fun find(id: UUID): AttendanceSession? = jpa.findById(id).orElse(null)?.toDomain()
    override fun approved(employeeId: UUID, from: LocalDate, to: LocalDate): List<AttendanceSession> = jpa.findByEmployeeIdAndWorkDateBetween(employeeId, from, to).map { it.toDomain() }
    private fun HrisAttendanceSessionJpaEntity.toDomain(): AttendanceSession {
        val session = AttendanceSession(id, tenantId ?: error("tenant missing"), employeeId ?: error("employee missing"), workDate, checkInAt, checkOutAt, com.duluin.ftth.hris.AttendanceDecision.valueOf(decision), gpsEvidence)
        session.revisions += revisions.findAllBySessionIdOrderByRevision(id).map { AttendanceRevision(it.id, it.sessionId!!, it.revision, it.checkInAt, it.checkOutAt, it.correctionId!!, it.approvedAt) }
        return session
    }
}

@Component
class HrisCorrectionPersistenceAdapter(private val jpa: HrisAttendanceCorrectionJpaRepository) : HrisCorrectionRepository {
    override fun save(correction: AttendanceCorrection): AttendanceCorrection {
        val entity = jpa.findById(correction.id).orElseGet { HrisAttendanceCorrectionJpaEntity(correction.id) }
        entity.sessionId = correction.sessionId; entity.requesterId = correction.requesterId; entity.custodianId = correction.custodianId
        entity.requestedCheckIn = correction.requestedCheckIn; entity.requestedCheckOut = correction.requestedCheckOut; entity.reason = correction.reason
        entity.state = correction.state.name; entity.approverId = correction.approverId; entity.decidedAt = correction.decidedAt
        jpa.save(entity); return correction
    }
    override fun find(id: UUID) = jpa.findById(id).orElse(null)?.let { AttendanceCorrection(it.id, it.sessionId!!, it.requesterId!!, it.custodianId, it.requestedCheckIn, it.requestedCheckOut, it.reason, CorrectionState.valueOf(it.state), it.approverId, it.decidedAt) }
}

@Component
class HrisOutcomePersistenceAdapter(private val jpa: HrisOutcomeJpaRepository, private val mapper: ObjectMapper) : HrisOutcomeStore {
    override fun find(tenantId: UUID, namespace: String, operationKey: String) = jpa.findByTenantIdAndNamespaceAndOperationKey(tenantId, namespace, operationKey)?.let { stored(it) }
    override fun record(tenantId: UUID, namespace: String, operationKey: String, payloadHash: String, payload: HrisOutcomePayload): HrisStoredOutcome {
        val entity = HrisOutcomeJpaEntity().apply { this.namespace = namespace; this.operationKey = operationKey; this.payloadHash = payloadHash; outcomeJson = mapper.writeValueAsString(payload) }
        return try { stored(jpa.save(entity)) } catch (_: DataIntegrityViolationException) { find(tenantId, namespace, operationKey) ?: throw IllegalStateException("Concurrent HRIS operation outcome unavailable") }
    }
    private fun stored(entity: HrisOutcomeJpaEntity): HrisStoredOutcome = HrisStoredOutcome(entity.payloadHash, mapper.readValue(entity.outcomeJson, HrisOutcomePayload::class.java))
}

@Component
class HrisEventPersistenceAdapter(private val jpa: HrisOutboxJpaRepository, private val audits: HrisAuditJpaRepository, private val mapper: ObjectMapper, private val current: CurrentUserProvider) : HrisEventStore {
    override fun append(tenantId: UUID, aggregateId: UUID, type: String, sequence: Long, occurredAt: java.time.Instant, detail: Map<String, Any?>) {
        val safe = detail.filterKeys { it in setOf("decision", "workDate", "reason", "from", "to", "state") }
        val payload = mapper.writeValueAsString(safe)
        val next = (jpa.findFirstByTenantIdAndAggregateIdOrderBySequenceDesc(tenantId, aggregateId)?.sequence ?: 0) + 1
        jpa.save(HrisOutboxJpaEntity().apply { this.aggregateId = aggregateId; eventType = type; this.sequence = next; this.payload = payload })
        audits.save(HrisAuditJpaEntity().apply { actorId = current.currentOrNull()?.userId; action = type; entityType = "HRIS"; entityId = aggregateId; this.detail = payload; this.occurredAt = occurredAt })
    }
}

@Component
class HrisPeriodPersistenceAdapter(private val jpa: HrisPeriodJpaRepository) : HrisPeriodStore {
    override fun requireOpen(tenantId: UUID, date: LocalDate) {
        val period = jpa.findByTenantIdAndValidFromLessThanEqualAndValidToGreaterThanEqual(tenantId, date, date)
        if (period?.closedAt != null && period.reopenedAt == null) throw com.duluin.ftth.common.domain.error.ConflictException("attendance period is closed")
    }
    override fun findCovering(tenantId: UUID, date: LocalDate): HrisPeriodRef? =
        jpa.findByTenantIdAndValidFromLessThanEqualAndValidToGreaterThanEqual(tenantId, date, date)?.let {
            HrisPeriodRef(it.id, it.validFrom, it.validTo, if (it.closedAt != null && it.reopenedAt == null) "CLOSED" else "OPEN")
        }
}

@Component
class HrisPolicyPersistenceAdapter(
    private val employees: HrisEmployeeJpaRepository,
    private val employment: HrisEmploymentJpaRepository,
    private val shifts: HrisShiftJpaRepository,
    private val rosters: HrisRosterJpaRepository,
    private val exceptions: HrisExceptionJpaRepository,
) : HrisPolicyRepository {
    override fun resolve(employeeId: UUID, workDate: LocalDate): HrisPolicySnapshot {
        val employee = employees.findById(employeeId).orElseThrow { com.duluin.ftth.common.domain.error.ConflictException("employee profile not found") }
        val domainEmployee = EmployeeProfile(employee.id, employee.tenantId ?: error("tenant missing"), employee.userId ?: error("user missing"), com.duluin.ftth.hris.EmployeeStatus.valueOf(employee.status), employee.custodianId)
        val employmentRows = employment.findByEmployeeIdAndValidFromLessThanEqualAndValidToGreaterThanEqual(employeeId, workDate, workDate)
        if (employmentRows.size > 1) throw com.duluin.ftth.common.domain.error.ConflictException("overlapping employment records")
        val rosterRows = rosters.findByEmployeeIdAndValidFromLessThanEqualAndValidToGreaterThanEqual(employeeId, workDate, workDate)
        if (rosterRows.size > 1) throw com.duluin.ftth.common.domain.error.ConflictException("overlapping roster records")
        val roster = rosterRows.singleOrNull()
        val shift = roster?.shiftId?.let { shifts.findById(it).orElseThrow { com.duluin.ftth.common.domain.error.ConflictException("roster shift not found") } }
        val policyRoster = roster?.let { RosterAssignment(it.id, employeeId, it.shiftId!!, it.validFrom, it.validTo) }
        val policyShift = shift?.let { Shift(it.id, it.tenantId ?: domainEmployee.tenantId, it.name, it.startTime, it.endTime) }
        val policyExceptions = exceptions.findByExceptionDateAndEmployeeIdIsNullOrExceptionDateAndEmployeeId(workDate, workDate, employeeId).map { LeaveHolidayException(it.id, it.employeeId, it.exceptionDate, ExceptionKind.valueOf(it.kind), it.reason) }
        return HrisPolicySnapshot(domainEmployee, policyShift, policyRoster, policyExceptions, workDate)
    }
}
