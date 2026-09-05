package com.duluin.ftth.order.adapter.inbound.web

import com.duluin.ftth.order.*
import com.duluin.ftth.order.ServiceAddress as OrderServiceAddress
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/orders")
class OrderController(private val orders: OrderApi) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('order.order.create')")
    fun create(@Valid @RequestBody request: CreateOrderRequest): OrderView = orders.create(request.toCommand())

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('order.order.view')")
    fun get(@PathVariable id: UUID): OrderView? = orders.find(id)

    @PostMapping("/{id}/{transition}")
    @PreAuthorize("@authz.can('order.order.manage')")
    fun transition(
        @PathVariable id: UUID,
        @PathVariable transition: OrderTransition,
        @Valid @RequestBody request: TransitionRequest,
    ): OrderView = orders.transition(request.toCommand(id, transition))
}

data class CreateOrderRequest(
    val customerId: UUID,
    @field:NotEmpty val lines: List<OrderLineRequest>,
    val serviceAddress: ServiceAddress,
    val appointment: AppointmentRequest? = null,
    @field:Valid val operation: OperationRequest,
) {
    fun toCommand() = CreateOrderCommand(
        lines = lines.map { OrderLineCommand(it.catalogItemId, it.description, it.quantity) },
        customerId = customerId,
        serviceAddress = OrderServiceAddress(serviceAddress.address, serviceAddress.city, serviceAddress.postalCode, serviceAddress.latitude, serviceAddress.longitude),
        appointment = appointment?.toValue(), operation = operation.toCommand(),
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
