package com.duluin.ftth.customer.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.customer.application.port.inbound.AttachOnuCommand
import com.duluin.ftth.customer.application.port.inbound.CustomerView
import com.duluin.ftth.customer.application.port.inbound.ManageCustomerUseCase
import com.duluin.ftth.customer.application.port.inbound.ManageOnuUseCase
import com.duluin.ftth.customer.application.port.inbound.ManageSubscriptionUseCase
import com.duluin.ftth.customer.application.port.inbound.OnuView
import com.duluin.ftth.customer.application.port.inbound.RegisterOnuCommand
import com.duluin.ftth.customer.application.port.inbound.SaveCustomerCommand
import com.duluin.ftth.customer.application.port.inbound.SaveSubscriptionCommand
import com.duluin.ftth.customer.application.port.inbound.SubscriptionView
import com.duluin.ftth.customer.domain.model.CustomerStatus
import com.duluin.ftth.customer.domain.model.OnuStatus
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
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
import java.math.BigDecimal
import java.util.UUID

/**
 * Langganan dan ONU dikelola sebagai sub-resource pelanggan: keduanya tidak
 * punya makna sendiri tanpa pemiliknya, dan setiap operasinya selalu bermula
 * dari halaman pelanggan.
 */
@RestController
@RequestMapping("/api/customers")
@Tag(name = "Customers")
@SecurityRequirement(name = "bearer-jwt")
class CustomerController(
    private val manageCustomer: ManageCustomerUseCase,
    private val manageSubscription: ManageSubscriptionUseCase,
    private val manageOnu: ManageOnuUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('customer.customer.view')")
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) status: CustomerStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<CustomerView> = PageResponse.from(
        manageCustomer.search(query.orEmpty(), status, PageRequest(page, size, sort = "code")),
    )

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('customer.customer.view')")
    fun get(@PathVariable id: UUID): CustomerView = manageCustomer.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('customer.customer.create')")
    fun create(@Valid @RequestBody request: CustomerRequest): CustomerView =
        manageCustomer.create(request.toCommand())

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('customer.customer.update')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: CustomerRequest): CustomerView =
        manageCustomer.update(id, request.toCommand())

    @PutMapping("/{id}/status")
    @PreAuthorize("@authz.can('customer.customer.update')")
    fun changeStatus(@PathVariable id: UUID, @RequestBody request: CustomerStatusRequest): CustomerView =
        manageCustomer.changeStatus(id, request.status)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('customer.customer.delete')")
    fun delete(@PathVariable id: UUID) = manageCustomer.delete(id)

    // ---- Langganan ----

    @GetMapping("/{id}/subscriptions")
    @PreAuthorize("@authz.can('customer.subscription.view')")
    fun listSubscriptions(@PathVariable id: UUID): List<SubscriptionView> = manageSubscription.listForCustomer(id)

    @PostMapping("/{id}/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('customer.subscription.update')")
    fun createSubscription(
        @PathVariable id: UUID,
        @Valid @RequestBody request: SubscriptionRequest,
    ): SubscriptionView = manageSubscription.create(id, request.toCommand())

    @PutMapping("/subscriptions/{subscriptionId}")
    @PreAuthorize("@authz.can('customer.subscription.update')")
    fun updateSubscription(
        @PathVariable subscriptionId: UUID,
        @Valid @RequestBody request: SubscriptionRequest,
    ): SubscriptionView = manageSubscription.update(subscriptionId, request.toCommand())

    @PostMapping("/subscriptions/{subscriptionId}/activate")
    @PreAuthorize("@authz.can('customer.subscription.update')")
    fun activateSubscription(@PathVariable subscriptionId: UUID): SubscriptionView =
        manageSubscription.activate(subscriptionId)

    @PostMapping("/subscriptions/{subscriptionId}/isolate")
    @PreAuthorize("@authz.can('customer.subscription.update')")
    fun isolateSubscription(@PathVariable subscriptionId: UUID): SubscriptionView =
        manageSubscription.isolate(subscriptionId)

    @PostMapping("/subscriptions/{subscriptionId}/terminate")
    @PreAuthorize("@authz.can('customer.subscription.update')")
    fun terminateSubscription(@PathVariable subscriptionId: UUID): SubscriptionView =
        manageSubscription.terminate(subscriptionId)

    // ---- ONU ----

    @GetMapping("/{id}/onus")
    @PreAuthorize("@authz.can('customer.onu.view')")
    fun listOnus(@PathVariable id: UUID): List<OnuView> = manageOnu.listForCustomer(id)

    @PostMapping("/{id}/onus")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('customer.onu.assign')")
    fun registerOnu(@PathVariable id: UUID, @Valid @RequestBody request: OnuRequest): OnuView =
        manageOnu.register(id, RegisterOnuCommand(request.serialNumber, request.model))

    @PostMapping("/onus/{onuId}/attach")
    @PreAuthorize("@authz.can('customer.onu.assign')")
    fun attachOnu(@PathVariable onuId: UUID, @Valid @RequestBody request: AttachOnuRequest): OnuView =
        manageOnu.attach(onuId, AttachOnuCommand(request.odpId, request.portNumber, request.installRxPowerDbm))

    @PostMapping("/onus/{onuId}/detach")
    @PreAuthorize("@authz.can('customer.onu.assign')")
    fun detachOnu(@PathVariable onuId: UUID): OnuView = manageOnu.detach(onuId)

    @PutMapping("/onus/{onuId}/status")
    @PreAuthorize("@authz.can('customer.onu.assign')")
    fun changeOnuStatus(@PathVariable onuId: UUID, @RequestBody request: OnuStatusRequest): OnuView =
        manageOnu.changeStatus(onuId, request.status)

    @DeleteMapping("/onus/{onuId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('customer.onu.assign')")
    fun deleteOnu(@PathVariable onuId: UUID) = manageOnu.delete(onuId)
}

data class CustomerLocationRequest(
    @field:Min(-180) @field:Max(180) val longitude: Double,
    @field:Min(-90) @field:Max(90) val latitude: Double,
)

data class CustomerRequest(
    /** Kosong = server membuat kode berurut otomatis (`CUST-000001`). */
    @field:Size(max = 40) val code: String? = null,
    @field:NotBlank @field:Size(max = 150) val name: String,
    @field:Size(max = 30) val phone: String? = null,
    @field:Size(max = 255) val email: String? = null,
    @field:NotBlank @field:Size(max = 500) val address: String,
    @field:Valid val location: CustomerLocationRequest,
    val areaId: UUID? = null,
)

data class CustomerStatusRequest(val status: CustomerStatus)

data class SubscriptionRequest(
    val planId: UUID,
    /** Harga negosiasi opsional; kosong = pakai harga paket. */
    @field:DecimalMin("0.0") val monthlyFeeOverride: BigDecimal? = null,
)

data class OnuRequest(
    @field:NotBlank @field:Size(max = 60) val serialNumber: String,
    @field:Size(max = 80) val model: String? = null,
)

data class AttachOnuRequest(
    val odpId: UUID,
    @field:Min(1) val portNumber: Int,
    @field:Min(-40) @field:Max(0) val installRxPowerDbm: Double? = null,
)

data class OnuStatusRequest(val status: OnuStatus)

private fun CustomerRequest.toCommand() = SaveCustomerCommand(
    code = code,
    name = name,
    phone = phone,
    email = email,
    address = address,
    location = Coordinate(location.longitude, location.latitude),
    areaId = areaId,
)

private fun SubscriptionRequest.toCommand() = SaveSubscriptionCommand(
    planId = planId,
    monthlyFeeOverride = monthlyFeeOverride,
)
