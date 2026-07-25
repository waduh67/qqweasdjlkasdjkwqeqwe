package com.duluin.ftth.workorder.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.workorder.application.port.inbound.ManageWorkOrderUseCase
import com.duluin.ftth.workorder.application.port.inbound.RecordOpticalCommand
import com.duluin.ftth.workorder.application.port.inbound.SaveWorkOrderCommand
import com.duluin.ftth.workorder.application.port.inbound.UpdateWorkOrderCommand
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderDashboardView
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderDetail
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderFilter
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderQuery
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderView
import com.duluin.ftth.workorder.domain.model.WorkOrderApprovalStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderPriority
import com.duluin.ftth.workorder.domain.model.WorkOrderStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Work order sisi operator/dispatcher: buat, tugaskan ke teknisi, dan kelola
 * lifecycle (draft → ditugaskan → dikerjakan → selesai, atau batal). Pengerjaan
 * lapangan oleh teknisi (mulai/selesai + bukti) dilayani klien terpisah nanti.
 */
@RestController
@RequestMapping("/api/work-orders")
@Tag(name = "Work Order")
@SecurityRequirement(name = "bearer-jwt")
class WorkOrderController(
    private val query: WorkOrderQuery,
    private val manage: ManageWorkOrderUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('workorder.order.view')")
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) type: WorkOrderType?,
        @RequestParam(required = false) status: WorkOrderStatus?,
        @RequestParam(required = false) assignedTo: UUID?,
        @RequestParam(required = false) approval: WorkOrderApprovalStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<WorkOrderView> = PageResponse.from(
        this.query.search(
            WorkOrderFilter(query = query, type = type, status = status, assignedTo = assignedTo, approvalStatus = approval),
            PageRequest(page, size, sort = "createdAt", descending = true),
        ),
    )

    @GetMapping("/dashboard")
    @PreAuthorize("@authz.can('workorder.dashboard.view')")
    fun dashboard(): WorkOrderDashboardView = query.dashboard()

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('workorder.order.view')")
    fun detail(@PathVariable id: UUID): WorkOrderDetail = query.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('workorder.order.create')")
    fun create(@Valid @RequestBody request: CreateWorkOrderRequest): WorkOrderView =
        manage.create(request.toCommand())

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('workorder.order.update')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateWorkOrderRequest): WorkOrderView =
        manage.update(id, request.toCommand())

    @PostMapping("/{id}/assign")
    @PreAuthorize("@authz.can('workorder.order.assign')")
    fun assign(@PathVariable id: UUID, @Valid @RequestBody request: AssignRequest): WorkOrderView =
        manage.assign(id, request.technicianId!!)

    @PostMapping("/{id}/start")
    @PreAuthorize("@authz.can('workorder.order.update')")
    fun start(@PathVariable id: UUID): WorkOrderView = manage.start(id)

    @PostMapping("/{id}/complete")
    @PreAuthorize("@authz.can('workorder.order.close')")
    fun complete(@PathVariable id: UUID, @Valid @RequestBody(required = false) request: CompleteRequest?): WorkOrderView =
        manage.complete(id, request?.resolutionNote)

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@authz.can('workorder.order.close')")
    fun cancel(@PathVariable id: UUID, @Valid @RequestBody(required = false) request: CancelRequest?): WorkOrderView =
        manage.cancel(id, request?.reason)

    @PutMapping("/{id}/optical")
    @PreAuthorize("@authz.can('workorder.order.update')")
    fun recordOptical(@PathVariable id: UUID, @Valid @RequestBody request: RecordOpticalRequest): WorkOrderView =
        manage.recordOptical(id, request.toCommand())

    @PostMapping("/{id}/approve")
    @PreAuthorize("@authz.can('workorder.order.approve')")
    fun approve(@PathVariable id: UUID, @Valid @RequestBody(required = false) request: ApproveRequest?): WorkOrderView =
        manage.approve(id, request?.note)

    @PostMapping("/{id}/reject")
    @PreAuthorize("@authz.can('workorder.order.approve')")
    fun reject(@PathVariable id: UUID, @Valid @RequestBody request: RejectRequest): WorkOrderView =
        manage.reject(id, request.reason!!)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('workorder.order.update')")
    fun delete(@PathVariable id: UUID) = manage.delete(id)
}

data class CreateWorkOrderRequest(
    @field:NotNull val type: WorkOrderType?,
    @field:NotBlank @field:Size(max = 200) val title: String?,
    @field:Size(max = 2000) val description: String?,
    val priority: WorkOrderPriority?,
    val customerId: UUID?,
    val incidentId: UUID?,
    val areaId: UUID?,
    val scheduledAt: Instant?,
    val assignedTo: UUID?,
) {
    fun toCommand() = SaveWorkOrderCommand(
        type = type!!,
        title = title!!,
        description = description,
        priority = priority ?: WorkOrderPriority.NORMAL,
        customerId = customerId,
        incidentId = incidentId,
        areaId = areaId,
        scheduledAt = scheduledAt,
        assignedTo = assignedTo,
    )
}

data class UpdateWorkOrderRequest(
    @field:NotBlank @field:Size(max = 200) val title: String?,
    @field:Size(max = 2000) val description: String?,
    val priority: WorkOrderPriority?,
    val customerId: UUID?,
    val incidentId: UUID?,
    val areaId: UUID?,
    val scheduledAt: Instant?,
) {
    fun toCommand() = UpdateWorkOrderCommand(
        title = title!!,
        description = description,
        priority = priority ?: WorkOrderPriority.NORMAL,
        customerId = customerId,
        incidentId = incidentId,
        areaId = areaId,
        scheduledAt = scheduledAt,
    )
}

data class AssignRequest(
    @field:NotNull val technicianId: UUID?,
)

data class CompleteRequest(
    @field:Size(max = 2000) val resolutionNote: String?,
)

data class CancelRequest(
    @field:Size(max = 500) val reason: String?,
)

/** Catatan persetujuan opsional (mis. kualitas pasang OK). */
data class ApproveRequest(
    @field:Size(max = 500) val note: String?,
)

/** Penolakan hasil kerja; alasan wajib agar teknisi tahu apa yang harus diperbaiki. */
data class RejectRequest(
    @field:NotBlank @field:Size(max = 500) val reason: String?,
)

/** Redaman optik (dBm); GPON selalu negatif, rentang wajar −40..0. Keduanya opsional. */
data class RecordOpticalRequest(
    @field:DecimalMin("-40.0") @field:DecimalMax("0.0") val rxBeforeDbm: Double?,
    @field:DecimalMin("-40.0") @field:DecimalMax("0.0") val rxAfterDbm: Double?,
) {
    fun toCommand() = RecordOpticalCommand(rxBeforeDbm = rxBeforeDbm, rxAfterDbm = rxAfterDbm)
}
