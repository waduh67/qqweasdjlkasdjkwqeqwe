package com.duluin.ftth.hris.domain

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.hris.AttendanceDecision
import com.duluin.ftth.hris.EmployeeStatus
import com.duluin.ftth.hris.ShiftRef
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

data class EffectiveEmployment(val id: UUID, val employeeId: UUID, val from: LocalDate, val to: LocalDate?, val title: String) {
    init { require(to == null || !to.isBefore(from)) { "employment dates are reversed" } }
    fun applies(date: LocalDate) = !date.isBefore(from) && (to == null || !date.isAfter(to))
}

data class Shift(val id: UUID, val tenantId: UUID, val name: String, val start: LocalTime, val end: LocalTime) {
    val overnight get() = end <= start
    fun workDate(at: LocalDateTime): LocalDate = if (overnight && at.toLocalTime() < end) at.toLocalDate().minusDays(1) else at.toLocalDate()
    fun contains(receivedAt: Instant, zone: java.time.ZoneId, date: LocalDate): Boolean {
        val local = receivedAt.atZone(zone).toLocalDateTime()
        if (workDate(local) != date) return false
        val time = local.toLocalTime()
        return if (overnight) time >= start || time < end else time >= start && time < end
    }
    fun ref() = ShiftRef(id, name, start, end)
}

data class RosterAssignment(val id: UUID, val employeeId: UUID, val shiftId: UUID, val from: LocalDate, val to: LocalDate?) {
    init { require(to == null || !to.isBefore(from)) { "roster dates are reversed" } }
    fun applies(date: LocalDate) = !date.isBefore(from) && (to == null || !date.isAfter(to))
}

enum class ExceptionKind { LEAVE, HOLIDAY }
data class LeaveHolidayException(val id: UUID, val employeeId: UUID?, val date: LocalDate, val kind: ExceptionKind, val reason: String)

data class EmployeeProfile(val id: UUID, val tenantId: UUID, val userId: UUID, val status: EmployeeStatus, val custodianId: UUID?) {
    fun requireActive() { if (status == EmployeeStatus.REVOKED) throw AccessDeniedException("Employee identity is revoked") }
}

data class AttendanceSession(
    val id: UUID,
    val tenantId: UUID,
    val employeeId: UUID,
    val workDate: LocalDate,
    val checkInAt: Instant,
    var checkOutAt: Instant? = null,
    var decision: AttendanceDecision = AttendanceDecision.REVIEW_REQUIRED,
    val gpsEvidence: Boolean = false,
    val events: MutableList<AttendanceEvent> = mutableListOf(),
    val revisions: MutableList<AttendanceRevision> = mutableListOf(),
) {
    fun checkOut(receivedAt: Instant) {
        if (receivedAt.isBefore(checkInAt)) throw ValidationException("check-out precedes check-in")
        if (checkOutAt != null) throw ConflictException("attendance session is already checked out")
        checkOutAt = receivedAt
        events += AttendanceEvent("AttendanceCheckedOut", receivedAt)
    }
}
data class AttendanceEvent(val type: String, val occurredAt: Instant)
data class AttendanceRevision(val id: UUID, val sessionId: UUID, val revision: Long, val checkInAt: Instant?, val checkOutAt: Instant?, val correctionId: UUID, val approvedAt: Instant)

enum class CorrectionState { PENDING, APPROVED, REJECTED }
data class AttendanceCorrection(
    val id: UUID,
    val sessionId: UUID,
    val requesterId: UUID,
    val custodianId: UUID?,
    val requestedCheckIn: Instant?,
    val requestedCheckOut: Instant?,
    val reason: String,
    var state: CorrectionState = CorrectionState.PENDING,
    var approverId: UUID? = null,
    var decidedAt: Instant? = null,
) {
    fun approve(actorId: UUID, at: Instant) {
        if (state != CorrectionState.PENDING) throw ConflictException("correction is already decided")
        if (actorId == requesterId || actorId == custodianId) throw AccessDeniedException("maker-checker approval is required")
        state = CorrectionState.APPROVED
        approverId = actorId
        decidedAt = at
    }
    fun reject(actorId: UUID) {
        if (state != CorrectionState.PENDING) throw ConflictException("correction is already decided")
        if (actorId == requesterId || actorId == custodianId) throw AccessDeniedException("maker-checker decision is required")
        state = CorrectionState.REJECTED
        approverId = actorId
        decidedAt = java.time.Instant.now()
    }
}

fun <T> rejectOverlappingEffectiveDates(records: List<T>, range: (T) -> Pair<LocalDate, LocalDate?>) {
    records.indices.forEach { first ->
        ((first + 1) until records.size).forEach { second ->
            val (aFrom, aTo) = range(records[first]); val (bFrom, bTo) = range(records[second])
            if ((aTo == null || !aTo.isBefore(bFrom)) && (bTo == null || !bTo.isBefore(aFrom))) throw ConflictException("effective-dated assignments overlap")
        }
    }
}

data class AttendancePeriod(val tenantId: UUID, val from: LocalDate, val to: LocalDate, var closedAt: Instant? = null, var reopenedAt: Instant? = null) {
    init { require(!to.isBefore(from)) { "period dates are reversed" } }
    fun contains(date: LocalDate) = date in from..to
    fun requireOpen() { if (closedAt != null && reopenedAt == null) throw ConflictException("attendance period is closed") }
    fun close(at: Instant) { if (closedAt != null && reopenedAt == null) throw ConflictException("attendance period is already closed"); closedAt = at; reopenedAt = null }
    fun reopen(at: Instant) { if (closedAt == null || reopenedAt != null) throw ConflictException("attendance period is not closed"); reopenedAt = at }
}

class HrisRules {
    fun decide(employee: EmployeeProfile, shift: Shift?, roster: RosterAssignment?, exceptions: List<LeaveHolidayException>, receivedAt: Instant, zone: java.time.ZoneId, workDate: LocalDate, gpsOnly: Boolean): AttendanceDecision {
        employee.requireActive()
        if (gpsOnly) return AttendanceDecision.REJECTED
        val exception = exceptions.firstOrNull { it.date == workDate && (it.employeeId == null || it.employeeId == employee.id) }
        if (exception != null) return AttendanceDecision.EXCUSED
        if (shift == null || roster == null || !roster.applies(workDate) || !shift.contains(receivedAt, zone, workDate)) return AttendanceDecision.REVIEW_REQUIRED
        return AttendanceDecision.ACCEPTED
    }
}
