package com.duluin.ftth.hris.adapter.inbound.web

import com.duluin.ftth.hris.application.port.HrisAttendanceUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@RestController
@RequestMapping("/api/hris/attendance")
class HrisAttendanceController(private val attendance: HrisAttendanceUseCase) {
    @PostMapping("/check-in")
    @PreAuthorize("@authz.can('hris.employee.self')")
    fun checkIn(@Valid @RequestBody request: CheckInRequest) = attendance.selfCheckIn(request.operationKey, request.payloadHash, request.gpsOnly)
    @PostMapping("/{sessionId}/check-out")
    @PreAuthorize("@authz.can('hris.employee.self')")
    fun checkOut(@PathVariable sessionId: UUID) = attendance.selfCheckOut(sessionId)
    @PostMapping("/{sessionId}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('hris.attendance.correct')")
    fun correct(@PathVariable sessionId: UUID, @Valid @RequestBody request: CorrectionRequest) = attendance.requestCorrection(sessionId, request.checkIn, request.checkOut, request.reason, request.operationKey, request.payloadHash)
    @PostMapping("/corrections/{id}/approve")
    @PreAuthorize("@authz.can('hris.attendance.review')")
    fun approve(@PathVariable id: UUID, @Valid @RequestBody request: MutationRequest) = attendance.approveCorrection(id, request.operationKey, request.payloadHash)
    @PostMapping("/corrections/{id}/reject")
    @PreAuthorize("@authz.can('hris.attendance.review')")
    fun reject(@PathVariable id: UUID, @Valid @RequestBody request: MutationRequest) = attendance.rejectCorrection(id, request.operationKey, request.payloadHash)
}

data class CheckInRequest(@field:NotBlank @field:Size(max = 240) val operationKey: String, @field:NotBlank @field:Size(min = 64, max = 64) val payloadHash: String, val gpsOnly: Boolean = false)
data class CorrectionRequest(val checkIn: Instant?, val checkOut: Instant?, @field:NotBlank @field:Size(max = 500) val reason: String, @field:NotBlank val operationKey: String, @field:NotBlank @field:Size(min = 64, max = 64) val payloadHash: String)
data class MutationRequest(@field:NotBlank @field:Size(max = 240) val operationKey: String, @field:NotBlank @field:Size(min = 64, max = 64) val payloadHash: String)
