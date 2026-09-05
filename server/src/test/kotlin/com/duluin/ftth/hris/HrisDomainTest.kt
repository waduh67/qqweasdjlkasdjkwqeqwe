package com.duluin.ftth.hris

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.hris.application.port.InMemoryHrisAttendanceRepository
import com.duluin.ftth.hris.application.port.InMemoryHrisCorrectionRepository
import com.duluin.ftth.hris.application.port.InMemoryHrisOutcomeStore
import com.duluin.ftth.hris.application.port.InMemoryHrisEventStore
import com.duluin.ftth.hris.application.service.HrisAttendanceService
import com.duluin.ftth.hris.domain.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.*
import java.util.UUID

class HrisDomainTest {
    private val tenant = UUID.randomUUID()
    private val employeeId = UUID.randomUUID()
    private val requester = UUID.randomUUID()
    private val approver = UUID.randomUUID()
    private val employee = EmployeeProfile(UUID.randomUUID(), tenant, UUID.randomUUID(), EmployeeStatus.ACTIVE, requester)
    private val shift = Shift(UUID.randomUUID(), tenant, "Night", LocalTime.of(22, 0), LocalTime.of(6, 0))
    private val roster = RosterAssignment(UUID.randomUUID(), employeeId, shift.id, LocalDate.of(2026, 9, 4), null)
    private val received = Instant.parse("2026-09-05T02:00:00Z")

    private fun service() = HrisAttendanceService(InMemoryHrisAttendanceRepository(), InMemoryHrisCorrectionRepository())

    @Test
    fun `replayed check-in returns original receipt and hash mismatch conflicts`() {
        val service = service()
        val first = service.checkIn(employee.copy(id = employeeId), shift, roster, emptyList(), received, ZoneOffset.UTC, LocalDate.of(2026, 9, 4), "one", "hash")
        assertThat(service.checkIn(employee.copy(id = employeeId), shift, roster, emptyList(), received.plusSeconds(5), ZoneOffset.UTC, LocalDate.of(2026, 9, 4), "one", "hash")).isEqualTo(first)
        assertThatThrownBy { service.checkIn(employee.copy(id = employeeId), shift, roster, emptyList(), received, ZoneOffset.UTC, LocalDate.of(2026, 9, 4), "one", "other") }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `overnight shift uses prior work date`() {
        val result = service().checkIn(employee.copy(id = employeeId), shift, roster, emptyList(), received, ZoneOffset.UTC, LocalDate.of(2026, 9, 4), "night", "hash")
        assertThat(result.decision).isEqualTo(AttendanceDecision.ACCEPTED)
        assertThat(result.workDate).isEqualTo(LocalDate.of(2026, 9, 4))
    }

    @Test
    fun `holiday takes precedence over roster and leave takes precedence over shift`() {
        val holiday = LeaveHolidayException(UUID.randomUUID(), null, LocalDate.of(2026, 9, 4), ExceptionKind.HOLIDAY, "public holiday")
        val result = service().checkIn(employee.copy(id = employeeId), shift, roster, listOf(holiday), received, ZoneOffset.UTC, holiday.date, "holiday", "hash")
        assertThat(result.decision).isEqualTo(AttendanceDecision.EXCUSED)
    }

    @Test
    fun `gps-only evidence is never an attendance authority`() {
        val result = service().checkIn(employee.copy(id = employeeId), shift, roster, emptyList(), received, ZoneOffset.UTC, roster.from, "gps", "hash", gpsOnly = true)
        assertThat(result.decision).isEqualTo(AttendanceDecision.REJECTED)
    }

    @Test
    fun `correction rejects requester and custodian approval`() {
        val custodian = UUID.randomUUID()
        val correction = AttendanceCorrection(UUID.randomUUID(), UUID.randomUUID(), requester, custodian, null, null, "forgot")
        assertThatThrownBy { correction.approve(requester, received) }.isInstanceOf(AccessDeniedException::class.java)
        assertThatThrownBy { correction.approve(custodian, received) }.isInstanceOf(AccessDeniedException::class.java)
        correction.approve(approver, received)
        assertThat(correction.state).isEqualTo(CorrectionState.APPROVED)
        assertThat(correction.decidedAt).isEqualTo(received)
    }

    @Test
    fun `period close and reopen enforce lock`() {
        val period = AttendancePeriod(tenant, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        period.close(received)
        assertThatThrownBy { period.requireOpen() }.isInstanceOf(ConflictException::class.java)
        period.reopen(received.plusSeconds(1))
        period.requireOpen()
    }

    @Test
    fun `revoked employee cannot check in and event order is deterministic`() {
        val revoked = employee.copy(id = employeeId, status = EmployeeStatus.REVOKED)
        assertThatThrownBy { service().checkIn(revoked, shift, roster, emptyList(), received, ZoneOffset.UTC, roster.from, "revoked", "hash") }.isInstanceOf(AccessDeniedException::class.java)
        val service = service()
        val active = service.checkIn(employee.copy(id = employeeId), shift, roster, emptyList(), received, ZoneOffset.UTC, roster.from, "events", "hash")
        service.checkOut(active.id, received.plusSeconds(10))
        assertThat(active.events.map { it.type }).containsExactly("AttendanceCheckedIn", "AttendanceCheckedOut")
    }

    @Test
    fun `effective dated assignments reject overlap deterministically`() {
        val first = LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 5)
        val second = LocalDate.of(2026, 9, 5) to null
        assertThatThrownBy { rejectOverlappingEffectiveDates(listOf(first, second)) { it } }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `approved facts use corrected timestamps and covering period provenance`() {
        val attendance = InMemoryHrisAttendanceRepository(); val corrections = InMemoryHrisCorrectionRepository(); val periods = com.duluin.ftth.hris.application.port.InMemoryHrisPeriodStore()
        val periodId = UUID.randomUUID(); periods.add(com.duluin.ftth.hris.application.port.HrisPeriodRef(periodId, roster.from, roster.from.plusDays(2), "OPEN"))
        val service = HrisAttendanceService(attendance, corrections, InMemoryHrisOutcomeStore(), InMemoryHrisEventStore(), periods)
        val session = service.checkIn(employee.copy(id = employeeId), shift, roster, emptyList(), received, ZoneOffset.UTC, roster.from, "correct-fact", "a".repeat(64))
        val correction = AttendanceCorrection(UUID.randomUUID(), session.id, requester, UUID.randomUUID(), received.plusSeconds(30), received.plusSeconds(600), "clock correction")
        service.submitCorrection(correction, "correction-request", "b".repeat(64))
        service.approveCorrection(correction.id, approver, received.plusSeconds(1), "correction-approve", "c".repeat(64))
        val fact = service.approvedAttendance(employeeId, roster.from, roster.from)
        assertThat(fact.single().checkOutAt).isEqualTo(received.plusSeconds(600))
        assertThat(fact.single().receivedAt).isEqualTo(received.plusSeconds(30))
        assertThat(fact.single().periodId).isEqualTo(periodId)
        assertThat(fact.single().revision).isEqualTo(1)
    }

    @Test
    fun `same check-in key cannot replay for another employee`() {
        val service = service(); val other = employee.copy(id = UUID.randomUUID(), userId = UUID.randomUUID())
        service.checkIn(employee.copy(id = employeeId), shift, roster, emptyList(), received, ZoneOffset.UTC, roster.from, "shared", "d".repeat(64))
        assertThatThrownBy { service.checkIn(other, shift, roster.copy(employeeId = other.id), emptyList(), received, ZoneOffset.UTC, roster.from, "shared", "d".repeat(64)) }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `attendance event sequences are monotonic across checkout and correction`() {
        val events = InMemoryHrisEventStore(); val attendance = InMemoryHrisAttendanceRepository(); val corrections = InMemoryHrisCorrectionRepository()
        val service = HrisAttendanceService(attendance, corrections, InMemoryHrisOutcomeStore(), events)
        val session = service.checkIn(employee.copy(id = employeeId), shift, roster, emptyList(), received, ZoneOffset.UTC, roster.from, "ordered", "e".repeat(64))
        service.checkOut(session.id, received.plusSeconds(1))
        val correction = AttendanceCorrection(UUID.randomUUID(), session.id, requester, UUID.randomUUID(), null, null, "reason")
        service.submitCorrection(correction, "ordered-correction", "f".repeat(64))
        assertThat(events.events.filter { it.aggregateId == session.id }.map { it.sequence }).containsExactly(1, 2, 3)
    }
}
