package com.karuhun.ftth.hris.application.port

import com.karuhun.ftth.hris.domain.AttendanceCorrection
import com.karuhun.ftth.hris.domain.AttendanceSession
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

interface HrisAttendanceUseCase {
    fun selfCheckIn(operationKey: String, payloadHash: String, gpsOnly: Boolean): AttendanceSession
    fun selfCheckOut(sessionId: UUID): AttendanceSession
    fun requestCorrection(sessionId: UUID, checkIn: Instant?, checkOut: Instant?, reason: String, operationKey: String, payloadHash: String): AttendanceCorrection
    fun approveCorrection(id: UUID, operationKey: String, payloadHash: String): AttendanceCorrection
    fun rejectCorrection(id: UUID, operationKey: String, payloadHash: String): AttendanceCorrection
}
