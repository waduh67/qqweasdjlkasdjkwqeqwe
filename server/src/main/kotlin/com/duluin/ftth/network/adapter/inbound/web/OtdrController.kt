package com.duluin.ftth.network.adapter.inbound.web

import com.duluin.ftth.network.application.port.inbound.OtdrTestUseCase
import com.duluin.ftth.network.application.port.inbound.OtdrTestView
import com.duluin.ftth.network.application.port.inbound.RecordOtdrTestCommand
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Hasil uji OTDR sebuah kabel — dicatat teknisi, diplot sebagai titik perkiraan
 * gangguan di peta. Bersarang di bawah kabel karena selalu menempel pada satu kabel.
 */
@RestController
@RequestMapping("/api/cables/{cableId}/otdr")
@Tag(name = "Network — OTDR")
@SecurityRequirement(name = "bearer-jwt")
class OtdrController(
    private val otdr: OtdrTestUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('network.otdr.view')")
    fun list(@PathVariable cableId: UUID): List<OtdrTestView> = otdr.list(cableId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('network.otdr.record')")
    fun record(@PathVariable cableId: UUID, @Valid @RequestBody request: OtdrTestRequest): OtdrTestView =
        otdr.record(cableId, request.toCommand())

    @DeleteMapping("/{testId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('network.otdr.record')")
    fun delete(@PathVariable cableId: UUID, @PathVariable testId: UUID) = otdr.delete(cableId, testId)
}

private fun OtdrTestRequest.toCommand() = RecordOtdrTestCommand(
    distanceMeters = distanceMeters,
    measuredFrom = measuredFrom,
    eventType = eventType,
    lossDb = lossDb,
    note = note,
    recordedAt = recordedAt,
)
