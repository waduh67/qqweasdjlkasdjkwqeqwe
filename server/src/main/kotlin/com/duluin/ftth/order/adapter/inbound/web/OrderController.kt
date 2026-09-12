package com.duluin.ftth.order.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.order.*
import com.duluin.ftth.order.ServiceAddress as OrderServiceAddress
import com.duluin.ftth.order.application.port.inbound.AcceptOrderCommand
import com.duluin.ftth.order.application.port.inbound.FlagOrderCommand
import com.duluin.ftth.order.application.port.inbound.MarkUnreachableCommand
import com.duluin.ftth.order.application.port.inbound.OrderAcceptanceUseCase
import com.duluin.ftth.order.application.port.inbound.OrderAttentionUseCase
import com.duluin.ftth.order.application.port.inbound.OrderQuery
import com.duluin.ftth.order.application.port.inbound.PromoteOrderLeadCommand
import com.duluin.ftth.order.domain.model.OrderPortalFlag
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
import jakarta.validation.constraints.Size
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
    private val acceptance: OrderAcceptanceUseCase,
    private val attention: OrderAttentionUseCase,
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

    /**
     * ACCEPT SENGAJA dibelokkan ke [OrderAcceptanceUseCase], bukan ke transisi biasa.
     *
     * Sejak P5.4 menerima pesanan berarti tiga hal sekaligus (terima → promosikan calon
     * pelanggan → buka WO PSB). Membelokkannya di sini, bukan membuat endpoint `/accept`
     * tersendiri, menutup satu celah: selama `POST /{id}/ACCEPT` masih ada dan hanya memindahkan
     * status, siapa pun yang memakainya — klien lama, skrip, atau UI yang belum diperbarui —
     * menghasilkan pesanan diterima yang tak pernah menjadi pekerjaan siapa pun.
     */
    @PostMapping("/{id}/{transition}")
    @PreAuthorize("@authz.can('order.order.manage')")
    fun transition(
        @PathVariable id: UUID,
        @PathVariable transition: OrderTransition,
        @Valid @RequestBody request: TransitionRequest,
    ): OrderView =
        if (transition == OrderTransition.ACCEPT) acceptance.accept(request.toAcceptCommand(id))
        else orders.transition(request.toCommand(id, transition))

    /**
     * Penanda portal (P4.7): memberi tahu PELANGGAN bahwa pesanannya menunggu sesuatu, tanpa
     * memindahkan status pesanannya. `flag = null` melepas penanda.
     *
     * `order.order.manage`, bukan `order.order.view`: ini menulis (ATURAN repo — hanya `*.view`
     * yang boleh dipakai untuk baca).
     */
    @PostMapping("/{id}/attention")
    @PreAuthorize("@authz.can('order.order.manage')")
    fun attention(
        @PathVariable id: UUID,
        @Valid @RequestBody request: AttentionRequest,
    ): OrderView = attention.flag(request.toCommand(id))

    /**
     * "Pelanggan tidak bisa dihubungi." Terpisah dari `/attention` karena di sini operator TIDAK
     * menulis kalimatnya — ia hanya menyatakan faktanya, dan sistem yang menyusun kalimat baku
     * untuk halaman lacak. Lihat `OrderAttentionUseCase.markUnreachable`.
     *
     * Ditolak 409 kalau pesanannya belum diterima, sudah punya janji temu, atau baru saja
     * diterima — ketiganya berarti bola masih di tangan KAMI, bukan pelanggan.
     */
    @PostMapping("/{id}/unreachable")
    @PreAuthorize("@authz.can('order.order.manage')")
    fun unreachable(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UnreachableRequest,
    ): OrderView = attention.markUnreachable(request.toCommand(id))
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
/**
 * [promotion] dan [assignees] HANYA dipakai saat transisinya `ACCEPT` (lihat
 * [OrderAcceptanceUseCase]); transisi lain mengabaikannya. Ia ditaruh di sini, bukan di endpoint
 * terpisah, supaya operator menerima pesanan dalam SATU permintaan — dua permintaan berarti ada
 * jeda di mana pesanan sudah diterima tapi pelanggannya belum ada.
 */
data class TransitionRequest(
    val expectedRevision: Long,
    val reason: String? = null,
    val appointment: AppointmentRequest? = null,
    @field:Valid val operation: OperationRequest,
    val promotion: PromoteLeadOnAcceptRequest? = null,
    val assignees: Set<UUID> = emptySet(),
) {
    fun toCommand(id: UUID, transition: OrderTransition) = OrderTransitionCommand(id, transition, expectedRevision, reason, appointment?.toValue(), operation.toCommand())

    fun toAcceptCommand(id: UUID) = AcceptOrderCommand(
        orderId = id,
        expectedRevision = expectedRevision,
        operation = operation.toCommand(),
        promotion = promotion?.toCommand() ?: PromoteOrderLeadCommand(),
        appointmentStartsAt = appointment?.startsAt,
        assignees = assignees,
    )
}

/** Rincian promosi calon pelanggan; semuanya opsional — server memakai data pesanan bila kosong. */
data class PromoteLeadOnAcceptRequest(
    val planId: UUID? = null,
    val code: String? = null,
    val areaId: UUID? = null,
    val idCardNumber: String? = null,
    val monthlyFeeOverride: java.math.BigDecimal? = null,
) {
    fun toCommand() = PromoteOrderLeadCommand(planId, code, areaId, idCardNumber, monthlyFeeOverride)
}

/**
 * [flag] null = LEPAS penanda. [reason] ikut terbaca PELANGGAN di halaman lacak, jadi ia harus
 * ditulis untuk pelanggan ("menunggu konfirmasi titik pemasangan"), bukan catatan internal.
 */
data class AttentionRequest(
    val flag: OrderPortalFlag? = null,
    @field:Size(max = 300) val reason: String? = null,
    @field:Valid val operation: OperationRequest,
) {
    fun toCommand(id: UUID) = FlagOrderCommand(id, flag, reason, operation.toCommand())
}

/**
 * SENGAJA tidak punya field `reason` yang tampil ke pelanggan — itulah bedanya dengan
 * [AttentionRequest]. [note] adalah catatan INTERNAL yang hanya masuk riwayat pesanan.
 */
data class UnreachableRequest(
    @field:Size(max = 300) val note: String? = null,
    @field:Valid val operation: OperationRequest,
) {
    fun toCommand(id: UUID) = MarkUnreachableCommand(id, note, operation.toCommand())
}
