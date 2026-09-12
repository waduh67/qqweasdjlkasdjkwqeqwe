package com.duluin.ftth.order.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.order.application.port.inbound.CreateOrderLeadCommand
import com.duluin.ftth.order.application.port.inbound.OrderLeadUseCase
import com.duluin.ftth.order.application.port.inbound.OrderLeadView
import com.duluin.ftth.order.application.port.inbound.PromoteOrderLeadCommand
import com.duluin.ftth.order.application.port.inbound.PromotedLeadView
import com.duluin.ftth.order.application.port.inbound.UpdateOrderLeadCommand
import com.duluin.ftth.order.application.port.outbound.OrderLeadFilter
import com.duluin.ftth.order.domain.model.LeadSource
import com.duluin.ftth.order.domain.model.LeadStatus
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

/**
 * Calon pelanggan (prospek) sisi OPERATOR.
 *
 * Prospek TIDAK pernah dihapus keras — ia dipindahkan ke status `DROPPED`. Menghapusnya akan
 * menghilangkan pesanan yang menunjuk lead itu (FK), dan menghapus jejak dari mana permintaan
 * pemasangan sebenarnya datang.
 */
@RestController
@RequestMapping("/api/orders/leads")
@Tag(name = "Order")
@SecurityRequirement(name = "bearer-jwt")
class OrderLeadController(private val leads: OrderLeadUseCase) {

    @GetMapping
    @PreAuthorize("@authz.can('order.lead.view')")
    @Suppress("LongParameterList")
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) status: LeadStatus?,
        @RequestParam(required = false) source: LeadSource?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<OrderLeadView> = PageResponse.from(
        leads.search(OrderLeadFilter(query = query, status = status, source = source), PageRequest(page, size)),
    )

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('order.lead.view')")
    fun get(@PathVariable id: UUID): OrderLeadView = leads.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('order.lead.manage')")
    fun create(@Valid @RequestBody request: CreateLeadRequest): OrderLeadView = leads.create(request.toCommand())

    @PatchMapping("/{id}")
    @PreAuthorize("@authz.can('order.lead.manage')")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateLeadRequest): OrderLeadView =
        leads.update(id, request.toCommand())

    @PostMapping("/{id}/status")
    @PreAuthorize("@authz.can('order.lead.manage')")
    fun changeStatus(@PathVariable id: UUID, @Valid @RequestBody request: LeadStatusRequest): OrderLeadView =
        leads.changeStatus(id, request.status!!)

    /**
     * Promosi jadi pelanggan. Dipakai saat pesanan diterima dan pemasangan dijadwalkan.
     * Idempoten: menekan tombolnya dua kali mengembalikan pelanggan yang sama.
     */
    @PostMapping("/{id}/promote")
    @PreAuthorize("@authz.can('order.lead.manage')")
    fun promote(@PathVariable id: UUID, @RequestBody request: PromoteLeadRequest): PromotedLeadView =
        leads.promote(id, request.toCommand())
}

data class CreateLeadRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val phone: String,
    val email: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val interestedPlanId: UUID? = null,
    val source: LeadSource = LeadSource.OPERATOR,
    val notes: String? = null,
) {
    fun toCommand() = CreateOrderLeadCommand(
        name = name, phone = phone, email = email, address = address, latitude = latitude,
        longitude = longitude, interestedPlanId = interestedPlanId, source = source, notes = notes,
    )
}

/** Field yang tidak dikirim = tidak diubah. Kosongkan data lewat layar khusus, bukan lewat PATCH parsial. */
data class UpdateLeadRequest(
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val interestedPlanId: UUID? = null,
    val notes: String? = null,
) {
    fun toCommand() = UpdateOrderLeadCommand(
        name = name, phone = phone, email = email, address = address, latitude = latitude,
        longitude = longitude, interestedPlanId = interestedPlanId, notes = notes,
    )
}

data class LeadStatusRequest(@field:NotNull val status: LeadStatus? = null)

data class PromoteLeadRequest(
    val planId: UUID? = null,
    val code: String? = null,
    val areaId: UUID? = null,
    val idCardNumber: String? = null,
    val monthlyFeeOverride: BigDecimal? = null,
) {
    fun toCommand() = PromoteOrderLeadCommand(
        planId = planId, code = code, areaId = areaId,
        idCardNumber = idCardNumber, monthlyFeeOverride = monthlyFeeOverride,
    )
}
