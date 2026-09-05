package com.duluin.ftth.hris.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.hris.*
import com.duluin.ftth.hris.application.port.HrisAttendanceRepository
import com.duluin.ftth.hris.application.port.HrisCorrectionRepository
import com.duluin.ftth.hris.application.port.HrisOutcomeStore
import com.duluin.ftth.hris.application.port.HrisEventStore
import com.duluin.ftth.hris.application.port.HrisOutcomePayload
import com.duluin.ftth.hris.application.port.InMemoryHrisOutcomeStore
import com.duluin.ftth.hris.application.port.InMemoryHrisEventStore
import com.duluin.ftth.hris.application.port.HrisPeriodStore
import com.duluin.ftth.hris.application.port.InMemoryHrisPeriodStore
import com.duluin.ftth.hris.domain.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.*
import java.util.UUID

@Service
class HrisAttendanceService(
    private val attendance: HrisAttendanceRepository,
    private val corrections: HrisCorrectionRepository,
    private val outcomes: HrisOutcomeStore = InMemoryHrisOutcomeStore(),
    private val events: HrisEventStore = InMemoryHrisEventStore(),
    private val periods: HrisPeriodStore = InMemoryHrisPeriodStore(),
    private val rules: HrisRules = HrisRules(),
) : HrisApi {
    @Transactional
    fun checkIn(employee: EmployeeProfile, shift: Shift?, roster: RosterAssignment?, exceptions: List<LeaveHolidayException>, receivedAt: Instant, zone: ZoneId, workDate: LocalDate, operationKey: String, payloadHash: String, gpsOnly: Boolean = false): AttendanceSession {
        if (operationKey.isBlank() || payloadHash.isBlank()) throw ConflictException("idempotency metadata is required")
        periods.requireOpen(employee.tenantId, workDate)
        val prior = outcomes.find(employee.tenantId, "attendance.check-in", operationKey)
        if (prior != null) {
            if (prior.payloadHash != payloadHash || prior.payload.targetId != employee.id || prior.payload.actorId != employee.userId) throw ConflictException("operation identity conflicts")
            return attendance.find(prior.payload.resultId) ?: throw ConflictException("durable outcome points to missing attendance")
        }
        val decision = rules.decide(employee, shift, roster, exceptions, receivedAt, zone, workDate, gpsOnly)
        val session = AttendanceSession(UUID.randomUUID(), employee.tenantId, employee.id, workDate, receivedAt, decision = decision, gpsEvidence = !gpsOnly)
        session.events += AttendanceEvent("AttendanceCheckedIn", receivedAt)
        return attendance.save(session).also {
            val stored = outcomes.record(employee.tenantId, "attendance.check-in", operationKey, payloadHash, HrisOutcomePayload(employee.id, employee.userId, it.id, "attendance", decision.name))
            if (stored.payload.resultId != it.id) return attendance.find(stored.payload.resultId) ?: it
            events.append(it.tenantId, it.id, "AttendanceCheckedIn", 1, receivedAt, mapOf("decision" to decision.name, "workDate" to workDate.toString()))
        }
    }
    @Transactional
    fun checkOut(sessionId: UUID, receivedAt: Instant) = attendance.find(sessionId)?.also { periods.requireOpen(it.tenantId, it.workDate); it.checkOut(receivedAt); attendance.save(it); events.append(it.tenantId, it.id, "AttendanceCheckedOut", 2, receivedAt) } ?: throw ConflictException("attendance session not found")
    @Transactional
    fun submitCorrection(correction: AttendanceCorrection, operationKey: String, payloadHash: String): AttendanceCorrection {
        val session = attendance.find(correction.sessionId) ?: throw ConflictException("correction session not found")
        periods.requireOpen(session.tenantId, session.workDate)
        val prior = outcomes.find(session.tenantId, "attendance.correction.request", operationKey)
        if (prior != null) { if (prior.payloadHash != payloadHash || prior.payload.targetId != correction.id) throw ConflictException("operation identity conflicts"); return corrections.find(prior.payload.resultId) ?: throw ConflictException("correction outcome missing") }
        corrections.save(correction); outcomes.record(session.tenantId, "attendance.correction.request", operationKey, payloadHash, HrisOutcomePayload(correction.id, correction.requesterId, correction.id, "correction", "PENDING")); events.append(session.tenantId, session.id, "AttendanceCorrectionRequested", 1, Instant.now(), mapOf("correctionId" to correction.id.toString())); return correction
    }
    @Transactional
    fun approveCorrection(id: UUID, actorId: UUID, at: Instant, operationKey: String, payloadHash: String): AttendanceCorrection = decideCorrection(id, actorId, at, operationKey, payloadHash, true)
    @Transactional
    fun rejectCorrection(id: UUID, actorId: UUID, at: Instant, operationKey: String, payloadHash: String): AttendanceCorrection = decideCorrection(id, actorId, at, operationKey, payloadHash, false)
    private fun decideCorrection(id: UUID, actorId: UUID, at: Instant, operationKey: String, payloadHash: String, approve: Boolean): AttendanceCorrection {
        val correction = corrections.find(id) ?: throw ConflictException("correction not found")
        val session = attendance.find(correction.sessionId) ?: throw ConflictException("correction session not found")
        periods.requireOpen(session.tenantId, session.workDate)
        val namespace = if (approve) "attendance.correction.approve" else "attendance.correction.reject"
        val prior = outcomes.find(session.tenantId, namespace, operationKey)
        if (prior != null) { if (prior.payloadHash != payloadHash || prior.payload.targetId != id) throw ConflictException("operation identity conflicts"); return correction }
        if (approve) {
            correction.approve(actorId, at)
            val requested = attendance.find(correction.sessionId) ?: throw ConflictException("correction session not found")
            requested.revisions += AttendanceRevision(UUID.randomUUID(), requested.id, requested.revisions.size.toLong() + 1, correction.requestedCheckIn ?: requested.checkInAt, correction.requestedCheckOut ?: requested.checkOutAt, correction.id, at)
            attendance.save(requested)
        } else { correction.reject(actorId); correction.decidedAt = at }
        corrections.save(correction); outcomes.record(session.tenantId, namespace, operationKey, payloadHash, HrisOutcomePayload(id, actorId, id, "correction", correction.state.name)); events.append(session.tenantId, session.id, if (approve) "AttendanceCorrectionApproved" else "AttendanceCorrectionRejected", 2, at, mapOf("correctionId" to id.toString())); return correction
    }
    override fun approvedAttendance(employeeId: UUID, from: LocalDate, to: LocalDate) = attendance.approved(employeeId, from, to).filter { it.decision == AttendanceDecision.ACCEPTED }.map { session ->
        val revision = session.revisions.maxByOrNull { it.revision }
        val period = periods.findCovering(session.tenantId, session.workDate)
        ApprovedAttendanceRef(session.tenantId, session.employeeId, session.id, session.workDate, session.decision, revision?.checkInAt ?: session.checkInAt, revision?.checkOutAt ?: session.checkOutAt, revision?.revision ?: 0, revision?.correctionId, revision?.approvedAt, period?.id, period?.status ?: "OPEN")
    }
}
