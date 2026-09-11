package com.duluin.ftth.monitoring.adapter.inbound.web

import com.duluin.ftth.monitoring.application.port.inbound.ManualOltPollResult
import com.duluin.ftth.monitoring.application.port.inbound.ManualOltPollUseCase
import com.duluin.ftth.monitoring.application.port.inbound.OltPollPersistenceException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/monitoring/olts")
@Tag(name = "Monitoring — Polling OLT")
@SecurityRequirement(name = "bearer-jwt")
class ManualOltPollingController(
    private val useCase: ManualOltPollUseCase,
) {
    @PostMapping("/{id}/poll")
    @PreAuthorize("@authz.can('monitoring.collector.manage')")
    @Operation(summary = "Jalankan polling SNMP OLT sekarang")
    fun poll(@PathVariable id: UUID): ManualOltPollResult = useCase.pollOlt(id)

    @ExceptionHandler(OltPollPersistenceException::class)
    fun handlePersistenceFailure(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, OltPollPersistenceException.PUBLIC_MESSAGE)
}
