package com.duluin.ftth.hris.adapter.inbound.web

import com.duluin.ftth.hris.application.service.HrisPeriodService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/hris/periods")
class HrisPeriodController(private val periods: HrisPeriodService) {
    @PostMapping
    @PreAuthorize("@authz.can('hris.period.close')")
    fun create(@Valid @RequestBody request: PeriodRequest) = periods.create(request.from, request.to, request.operationKey, request.payloadHash)
    @PostMapping("/{id}/close")
    @PreAuthorize("@authz.can('hris.period.close')")
    fun close(@PathVariable id: UUID, @Valid @RequestBody request: DecisionRequest) = periods.close(id, request.reason, request.operationKey, request.payloadHash)
    @PostMapping("/{id}/reopen")
    @PreAuthorize("@authz.can('hris.period.reopen')")
    fun reopen(@PathVariable id: UUID, @Valid @RequestBody request: DecisionRequest) = periods.reopen(id, request.reason, request.operationKey, request.payloadHash)
}
data class PeriodRequest(val from: LocalDate, val to: LocalDate, @field:NotBlank @field:Size(max = 240) val operationKey: String, @field:NotBlank @field:Size(min = 64, max = 64) val payloadHash: String)
data class DecisionRequest(@field:NotBlank @field:Size(max = 500) val reason: String, @field:NotBlank @field:Size(max = 240) val operationKey: String, @field:NotBlank @field:Size(min = 64, max = 64) val payloadHash: String)
