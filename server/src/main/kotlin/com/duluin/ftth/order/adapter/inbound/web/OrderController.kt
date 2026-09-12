package com.duluin.ftth.order.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.order.*
import com.duluin.ftth.order.ServiceAddress as OrderServiceAddress
import com.duluin.ftth.order.application.port.inbound.OrderQuery
import com.duluin.ftth.order.application.port.inbound.OrderSummaryView
import com.duluin.ftth.order.application.port.inbound.OrderTimelineEntryView
import com.duluin.ftth.order.application.port.outbound.OrderSearchFilter
import com.duluin.ftth.order.domain.model.OrderStatus
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

/**
 * Pesanan sisi OPERATOR. Pintu publik/anonim BELUM ada di fase ini — seluruh endpoint di sini
 * menuntut JWT dan permission `order.*`.
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Order")
@SecurityRequirement(name = "bearer-jwt")
class OrderController(
    private val orders: OrderApi,
    private val query: OrderQuery,
) {
    /**
     * Antrean pesanan. Tanpa ini back-office hanya bisa membuka pesanan yang id-nya sudah
     * diketahui — artinya tidak ada antrean sama sekali.
     */
    @GetMapping
    @PreAuthorize("@authz.can('order.order.view')")
    @Suppress("LongParameterList")
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) status: OrderStatus?,
        @RequestParam(required = false) createdFrom: Instant?,
        @RequestParam(required = false) createdTo: Instant?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<OrderSummaryView> = PageResponse.from(
        this.query.search(
            OrderSearchFilter(query = query, status = status, createdFrom = createdFrom, createdTo = createdTo),
            // Yang paling baru masuk di atas: antrean pesanan dikerjakan dari ujung terbaru.
            PageRequest(page, size),
        ),
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('order.order.create')")
    fun create(@Valid @RequestBody request: CreateOrderRequest): OrderView = orders.create(request.toCommand())

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('order.order.view')")
    fun get(@PathVariable id: UUID): OrderView? = orders.find(id)

    /** Riwayat lengkap satu pesanan — sumbernya `order_audit`, bukan rekonstruksi dari status sekarang. */
    @GetMapping("/{id}/timeline")
    @PreAuthorize("@authz.can('order.order.view')")
    fun timeline(@PathVariable id: UUID): List<OrderTimelineEntryView> = query.timeline(id)

    @PostMapping("/{id}/{transition}")
    @PreAuthorize("@authz.can('order.order.manage')")
    fun transition(
        @PathVariable id: UUID,
        @PathVariable transition: OrderTransition,
        @Valid @RequestBody request: TransitionRequest,
    ): OrderView = orders.transition(request.toCommand(id, transition))
}

/**
 * TEPAT SATU dari [customerId] atau [leadId] wajib diisi — validasinya di agregat, bukan di
 * anotasi, supaya jalur impor CSV nanti tunduk pada aturan yang sama.
 */
data class CreateOrderRequest(
    val customerId: UUID? = null,
    val leadId: UUID? = null,
    @field:NotEmpty val lines: List<OrderLineRequest>,
    val serviceAddress: ServiceAddress,
    val appointment: AppointmentRequest? = null,
    @field:Valid val operation: OperationRequest,
) {
    fun toCommand() = CreateOrderCommand(
        lines = lines.map { OrderLineCommand(it.catalogItemId, it.description, it.quantity) },
        customerId = customerId,
        serviceAddress = OrderServiceAddress(serviceAddress.address, serviceAddress.city, serviceAddress.postalCode, serviceAddress.latitude, serviceAddress.longitude),
        appointment = appointment?.toValue(), operation = operation.toCommand(), leadId = leadId,
    )
}

data class OrderLineRequest(val catalogItemId: UUID, @field:NotBlank val description: String, @field:Positive val quantity: Int)
data class ServiceAddress(@field:NotBlank val address: String, @field:NotBlank val city: String, @field:NotBlank val postalCode: String, val latitude: Double? = null, val longitude: Double? = null)
data class AppointmentRequest(val startsAt: Instant, val endsAt: Instant) {
    fun toValue() = Appointment(startsAt, endsAt)
}
data class OperationRequest(@field:NotBlank val namespace: String, @field:NotBlank val key: String, @field:NotBlank val payloadHash: String) {
    fun toCommand() = OperationCommand(namespace, key, payloadHash)
}
data class TransitionRequest(val expectedRevision: Long, val reason: String? = null, val appointment: AppointmentRequest? = null, @field:Valid val operation: OperationRequest) {
    fun toCommand(id: UUID, transition: OrderTransition) = OrderTransitionCommand(id, transition, expectedRevision, reason, appointment?.toValue(), operation.toCommand())
}
