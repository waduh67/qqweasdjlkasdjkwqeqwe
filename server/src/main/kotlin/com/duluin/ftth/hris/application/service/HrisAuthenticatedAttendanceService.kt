package com.duluin.ftth.hris.application.service

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.hris.application.port.*
import com.duluin.ftth.hris.adapter.outbound.persistence.HrisEmployeeJpaRepository
import com.duluin.ftth.hris.domain.*
import org.springframework.stereotype.Service
import java.time.*
import java.util.UUID

@Service
class HrisAuthenticatedAttendanceService(
    private val current: CurrentUserProvider,
    private val employees: HrisEmployeeJpaRepository,
    private val attendance: HrisAttendanceRepository,
    private val corrections: HrisCorrectionRepository,
    private val service: HrisAttendanceService,
    private val policy: HrisPolicyRepository,
) : HrisAttendanceUseCase {
    override fun selfCheckIn(operationKey: String, payloadHash: String, gpsOnly: Boolean): AttendanceSession {
        val actor = current.current()
        val entity = employees.findByUserId(actor.userId) ?: throw AccessDeniedException("Employee profile is unavailable")
        val snapshot = policy.resolve(entity.id, LocalDate.now(ZoneOffset.UTC))
        return service.checkIn(snapshot.employee, snapshot.shift, snapshot.roster, snapshot.exceptions, Instant.now(), ZoneOffset.UTC, snapshot.workDate, operationKey, payloadHash, gpsOnly)
    }
    override fun selfCheckOut(sessionId: UUID): AttendanceSession {
        val actor = current.current(); val session = attendance.find(sessionId) ?: throw ConflictException("attendance session not found")
        if (session.tenantId != actor.tenantId || session.employeeId != employees.findByUserId(actor.userId)?.id) throw AccessDeniedException("attendance session is not yours")
        return service.checkOut(sessionId, Instant.now())
    }
    override fun requestCorrection(sessionId: UUID, checkIn: Instant?, checkOut: Instant?, reason: String, operationKey: String, payloadHash: String): AttendanceCorrection {
        val actor = current.current(); val session = attendance.find(sessionId) ?: throw ConflictException("attendance session not found")
        if (session.tenantId != actor.tenantId || session.employeeId != employees.findByUserId(actor.userId)?.id) throw AccessDeniedException("attendance session is not yours")
        return service.submitCorrection(AttendanceCorrection(UUID.randomUUID(), sessionId, actor.userId, employees.findByUserId(actor.userId)?.custodianId, checkIn, checkOut, reason), operationKey, payloadHash)
    }
    override fun approveCorrection(id: UUID, operationKey: String, payloadHash: String): AttendanceCorrection {
        val actor = current.current(); val correction = corrections.find(id) ?: throw ConflictException("correction not found"); val session = attendance.find(correction.sessionId) ?: throw ConflictException("correction session not found")
        if (session.tenantId != actor.tenantId || correction.requesterId == actor.userId || correction.custodianId == actor.userId) throw AccessDeniedException("reviewer is outside the correction scope")
        return service.approveCorrection(id, actor.userId, Instant.now(), operationKey, payloadHash)
    }
    override fun rejectCorrection(id: UUID, operationKey: String, payloadHash: String): AttendanceCorrection {
        val actor = current.current(); val correction = corrections.find(id) ?: throw ConflictException("correction not found"); val session = attendance.find(correction.sessionId) ?: throw ConflictException("correction session not found")
        if (session.tenantId != actor.tenantId || correction.requesterId == actor.userId || correction.custodianId == actor.userId) throw AccessDeniedException("reviewer is outside the correction scope")
        return service.rejectCorrection(id, actor.userId, Instant.now(), operationKey, payloadHash)
    }
}
