package com.duluin.ftth.fieldservice.adapter.inbound.web

import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.fieldservice.application.port.inbound.CreateVisitCommand
import com.duluin.ftth.fieldservice.application.port.inbound.FieldServiceUseCase
import com.duluin.ftth.fieldservice.domain.model.AttendanceDecision
import com.duluin.ftth.fieldservice.domain.model.CommandMetadata
import com.duluin.ftth.fieldservice.domain.model.Visit
import com.duluin.ftth.fieldservice.application.service.FieldServiceService
import com.duluin.ftth.fieldservice.application.service.VisitListScope
import com.duluin.ftth.fieldservice.application.service.FieldServiceVisitView
import com.duluin.ftth.fieldservice.domain.model.VisitState
import com.duluin.ftth.fieldservice.application.port.outbound.CommandOutcomeStore
import com.duluin.ftth.fieldservice.application.port.outbound.VisitRepository
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.workorder.WorkorderApi
import com.duluin.ftth.iam.IamApi
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/fieldservice/visits")
class FieldServiceController(
    private val fieldService: FieldServiceService,
    private val currentUser: CurrentUserProvider,
) {
    @GetMapping
    @PreAuthorize("@authz.canAny('workorder.order.field','fieldservice.visit.view','workorder.order.view')")
    fun list(
        @RequestParam(defaultValue = "SELF") scope: VisitListScope,
        @RequestParam(required = false) status: VisitState?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<FieldServiceVisitView> = PageResponse.from(
        fieldService.listForHttp(currentUser.current(), scope, status, PageRequest(page, size, sort = "createdAt", descending = true)),
    )

    @PostMapping
    @PreAuthorize("@authz.canAny('workorder.order.create','workorder.order.field','fieldservice.visit.manage')")
    fun create(@Valid @RequestBody request: CreateVisitRequest): VisitResponse =
        fieldService.create(
            CreateVisitCommand(
                tenantId = currentUser.current().tenantId,
                orderId = request.orderId,
                workOrderId = request.workOrderId,
                technicianId = request.technicianId,
                plannedAt = request.plannedAt,
                operation = metadata(request.namespace, request.operationKey, request.payloadHash, request.revision),
            ),
        ).toResponse()

    @GetMapping("/{id}")
    @PreAuthorize("@authz.canAny('workorder.order.field','fieldservice.visit.view','fieldservice.visit.manage')")
    fun get(@PathVariable id: UUID): VisitResponse {
        val actor = currentUser.current()
        val visit = fieldService.readableForHttp(actor, id) ?: throw NoSuchElementException("Visit tidak ditemukan")
        return visit.toResponse()
    }

    @GetMapping("/{id}/work-session")
    @PreAuthorize("@authz.canAny('workorder.order.field','fieldservice.session.view','fieldservice.visit.view')")
    fun workSession(@PathVariable id: UUID): WorkSessionResponse {
        val actor = currentUser.current()
        val visit = fieldService.readableForHttp(actor, id) ?: throw NoSuchElementException("Visit tidak ditemukan")
        val session = fieldService.workSessionForHttp(actor.tenantId, visit.id) ?: throw NoSuchElementException("Work session tidak ditemukan")
        return WorkSessionResponse(session.id, session.visitId, session.startedAt, session.endedAt, session.submittedAt)
    }

    @PostMapping("/{id}/check-in")
    @PreAuthorize("@authz.canAny('workorder.order.field','fieldservice.visit.manage')")
    fun checkIn(@PathVariable id: UUID, @Valid @RequestBody request: AttendanceRequest): VisitResponse =
        fieldService.checkIn(id, metadata(request.namespace, request.operationKey, request.payloadHash, request.revision), Instant.now(), request.decision, request.reason).toResponse()

    @PostMapping("/{id}/on-site")
    @PreAuthorize("@authz.canAny('workorder.order.field','fieldservice.visit.manage')")
    fun onSite(@PathVariable id: UUID, @Valid @RequestBody request: OperationRequest): VisitResponse =
        fieldService.onSite(id, metadata(request.namespace, request.operationKey, request.payloadHash, request.revision), Instant.now()).toResponse()

    @PostMapping("/{id}/check-out")
    @PreAuthorize("@authz.canAny('workorder.order.field','fieldservice.visit.manage')")
    fun checkOut(@PathVariable id: UUID, @Valid @RequestBody request: OperationRequest): VisitResponse =
        fieldService.checkOut(id, metadata(request.namespace, request.operationKey, request.payloadHash, request.revision), Instant.now()).toResponse()

    @PostMapping("/{id}/submit")
    @PreAuthorize("@authz.canAny('workorder.order.field','fieldservice.visit.manage')")
    fun submit(@PathVariable id: UUID, @Valid @RequestBody request: OperationRequest): VisitResponse =
        fieldService.submit(id, metadata(request.namespace, request.operationKey, request.payloadHash, request.revision), Instant.now()).toResponse()

    private fun metadata(namespace: String, key: String, hash: String, revision: Long) =
        CommandMetadata(currentUser.current().tenantId, currentUser.current().userId, namespace, key, hash, revision,
            supervisor = currentUser.current().hasPermission("fieldservice.visit.manage"))

}

@Configuration
class FieldServiceHttpConfiguration {
    @Bean
    fun fieldServiceService(visits: VisitRepository, outcomes: CommandOutcomeStore, workorders: WorkorderApi, iam: IamApi) =
        FieldServiceService(visits, outcomes, workorders, iam::findUser)

    @Bean
    fun fieldServiceUseCase(service: FieldServiceService): FieldServiceUseCase = service
}

data class OperationRequest(
    @field:NotBlank val namespace: String,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val payloadHash: String,
    @field:NotNull val revision: Long,
)

private fun Visit.toResponse() = VisitResponse(id, state.name, revision, attendance?.decision, attendance?.serverReceivedAt)

data class WorkSessionResponse(val id: UUID, val visitId: UUID, val startedAt: Instant?, val endedAt: Instant?, val submittedAt: Instant?)
