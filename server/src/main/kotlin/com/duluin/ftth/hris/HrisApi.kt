package com.duluin.ftth.hris

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

interface HrisApi {
    fun approvedAttendance(employeeId: UUID, from: LocalDate, to: LocalDate): List<ApprovedAttendanceRef>
}

data class ApprovedAttendanceRef(
    val tenantId: UUID,
    val employeeId: UUID,
    val sessionId: UUID,
    val workDate: LocalDate,
    val decision: AttendanceDecision,
    val receivedAt: Instant,
    val checkOutAt: Instant? = null,
    val revision: Long = 0,
    val correctionId: UUID? = null,
    val approvedAt: Instant? = null,
    val periodId: UUID? = null,
    val periodStatus: String = "OPEN",
)

data class HrisAttendanceEvent(
    val tenantId: UUID,
    val aggregateId: UUID,
    val sequence: Long,
    val type: String,
    val occurredAt: Instant,
)

enum class AttendanceDecision { ACCEPTED, REVIEW_REQUIRED, REJECTED, EXCUSED }
enum class EmployeeStatus { ACTIVE, REVOKED }

data class ShiftRef(val id: UUID, val name: String, val start: LocalTime, val end: LocalTime) {
    val overnight: Boolean get() = end <= start
}
