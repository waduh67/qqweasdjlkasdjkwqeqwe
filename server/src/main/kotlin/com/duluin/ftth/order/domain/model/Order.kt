package com.duluin.ftth.order.domain.model

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.order.*
import com.duluin.ftth.common.domain.UuidV7
import java.util.UUID

enum class OrderStatus { DRAFT, SUBMITTED, ACCEPTED, SCHEDULED, FULFILLING, FULFILLED, CANCELLED, REJECTED }

@Suppress("LongParameterList")
class Order private constructor(
    val id: UUID,
    val tenantId: UUID,
    /**
     * Pemesan adalah TEPAT SATU dari [customerId] atau [leadId] — pelanggan yang sudah
     * terdaftar, atau calon pelanggan yang belum. Keduanya terisi berarti satu orang punya
     * dua identitas dan laporan menghitungnya dua kali; keduanya kosong berarti pesanan
     * yatim yang tak bisa ditindaklanjuti. Constraint yang sama ditegakkan di DB (V177).
     */
    val customerId: UUID?,
    val leadId: UUID?,
    /** Nomor yang bisa dibacakan lewat telepon, `ORD-YYMM-NNNN`. Dibuat di luar agregat (lihat OrderNumberGenerator). */
    val orderNumber: String,
    val lines: List<OrderLineCommand>,
    val serviceAddress: ServiceAddress,
    var appointment: Appointment?,
    var status: OrderStatus,
    var cancellationReason: String?,
    var rejectionReason: String?,
    var revision: Long,
    var lastActorId: UUID?,
    var lastOperation: OperationCommand,
) {
    companion object {
        fun create(command: CreateOrderCommand, tenantId: UUID, actorId: UUID?, orderNumber: String): Order {
            if (command.lines.isEmpty()) throw ValidationException("Order harus memiliki line")
            command.lines.forEach {
                if (it.quantity <= 0 || it.description.isBlank()) throw ValidationException("Line order tidak valid")
            }
            if ((command.customerId == null) == (command.leadId == null)) {
                throw ValidationException("Order harus punya tepat satu pemesan: pelanggan atau calon pelanggan")
            }
            if (orderNumber.isBlank()) throw ValidationException("Nomor pesanan wajib diisi")
            if (command.serviceAddress.address.isBlank() || command.serviceAddress.city.isBlank() || command.serviceAddress.postalCode.isBlank()) {
                throw ValidationException("Alamat layanan tidak lengkap")
            }
            val latitude = command.serviceAddress.latitude
            val longitude = command.serviceAddress.longitude
            if ((latitude == null) != (longitude == null)) throw ValidationException("Koordinat harus berpasangan")
            if (latitude != null && longitude != null && (latitude !in -90.0..90.0 || longitude !in -180.0..180.0)) {
                throw ValidationException("Koordinat layanan tidak valid")
            }
            command.appointment?.let {
                if (!it.startsAt.isBefore(it.endsAt)) throw ValidationException("Appointment tidak valid")
            }
            return Order(
                UuidV7.generate(), tenantId, command.customerId, command.leadId, orderNumber,
                command.lines.toList(), command.serviceAddress,
                command.appointment, OrderStatus.DRAFT, null, null, 0, actorId, command.operation,
            )
        }

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            customerId: UUID?,
            leadId: UUID?,
            orderNumber: String,
            lines: List<OrderLineCommand>,
            serviceAddress: ServiceAddress,
            appointment: Appointment?,
            status: OrderStatus,
            cancellationReason: String?,
            rejectionReason: String?,
            revision: Long,
            lastActorId: UUID?,
            lastOperation: OperationCommand,
        ) = Order(id, tenantId, customerId, leadId, orderNumber, lines, serviceAddress, appointment, status,
            cancellationReason, rejectionReason, revision, lastActorId, lastOperation)
    }

    fun transition(command: OrderTransitionCommand, actorId: UUID?) {
        if (command.expectedRevision != revision) throw ConflictException("Revision order sudah berubah")
        val target = when (command.transition) {
            OrderTransition.SUBMIT -> requireFrom(OrderStatus.SUBMITTED, OrderStatus.DRAFT)
            OrderTransition.ACCEPT -> requireFrom(OrderStatus.ACCEPTED, OrderStatus.SUBMITTED)
            OrderTransition.SCHEDULE -> requireFrom(OrderStatus.SCHEDULED, OrderStatus.ACCEPTED)
            OrderTransition.START_FULFILLING -> requireFrom(OrderStatus.FULFILLING, OrderStatus.SCHEDULED)
            OrderTransition.FULFILL -> requireFrom(OrderStatus.FULFILLED, OrderStatus.FULFILLING)
            OrderTransition.CANCEL -> requireFrom(OrderStatus.CANCELLED, OrderStatus.SUBMITTED, OrderStatus.ACCEPTED, OrderStatus.SCHEDULED)
            OrderTransition.REJECT -> requireFrom(OrderStatus.REJECTED, OrderStatus.SUBMITTED)
        }
        if (command.transition == OrderTransition.CANCEL && command.reason.isNullOrBlank()) throw ValidationException("Alasan pembatalan wajib diisi")
        if (command.transition == OrderTransition.REJECT && command.reason.isNullOrBlank()) throw ValidationException("Alasan penolakan wajib diisi")
        if (command.transition == OrderTransition.SCHEDULE && command.appointment == null) throw ValidationException("Appointment wajib diisi")
        status = target
        if (command.transition == OrderTransition.CANCEL) cancellationReason = command.reason
        if (command.transition == OrderTransition.REJECT) rejectionReason = command.reason
        if (command.appointment != null) appointment = command.appointment
        revision += 1
        lastActorId = actorId
        lastOperation = command.operation
    }

    private fun requireFrom(target: OrderStatus, vararg expected: OrderStatus): OrderStatus {
        if (status !in expected) throw ConflictException("Transisi ${status.name} ke ${target.name} tidak diizinkan")
        return target
    }
}

/**
 * Pandangan pesanan yang boleh dilihat PELANGGAN. `null` = pesanan belum layak tampil di
 * portal: DRAFT masih milik operator dan belum tentu pernah benar-benar dipesan.
 *
 * Tinggal di domain (bukan di adapter) karena portal dan konsol harus melihat pemetaan
 * status yang SAMA — dua salinan aturan ini akan berbeda diam-diam begitu status baru
 * ditambahkan, dan pelanggan akan melihat status yang tak pernah dimaksudkan bocor.
 */
fun Order.toPortalView(): PortalOrderView? {
    val portalStatus = when (status) {
        OrderStatus.DRAFT -> null
        OrderStatus.SUBMITTED -> PortalOrderStatus.RECEIVED
        OrderStatus.ACCEPTED -> PortalOrderStatus.REVIEWING
        OrderStatus.SCHEDULED -> PortalOrderStatus.SCHEDULED
        OrderStatus.FULFILLING -> PortalOrderStatus.IN_PROGRESS
        OrderStatus.FULFILLED -> PortalOrderStatus.COMPLETED
        OrderStatus.CANCELLED, OrderStatus.REJECTED -> PortalOrderStatus.CANCELLED
    } ?: return null
    return PortalOrderView(
        id = id,
        orderNumber = orderNumber,
        status = portalStatus,
        lines = lines.map { OrderLineView(it.catalogItemId, it.description, it.quantity) },
        // Koordinat SENGAJA tidak ikut: portal hanya perlu memastikan alamatnya benar.
        serviceAddress = PortalServiceAddress(serviceAddress.address, serviceAddress.city, serviceAddress.postalCode),
        appointment = appointment,
        revision = revision,
    )
}
