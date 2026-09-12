package com.duluin.ftth.order

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Public order boundary. Subscription ownership remains in customer. */
interface OrderApi {
    fun create(command: CreateOrderCommand): OrderView
    fun transition(command: OrderTransitionCommand): OrderView
    fun find(id: UUID): OrderView?
    fun portalOrders(customerId: UUID): List<PortalOrderView>
    fun portalOrder(customerId: UUID, orderId: UUID): PortalOrderView?
    fun applyFulfillment(command: OrderFulfillmentCommand): OrderFulfillmentResult
    fun fulfillmentRevision(orderId: UUID): Long?
}

/**
 * [actorId] adalah pelaku yang DICATAT di riwayat pesanan. Ia dikirim eksplisit karena efek
 * fulfillment dijalankan worker outbox tanpa `SecurityContext`; tanpa ini riwayat pesanan yang
 * berubah jadi `FULFILLED` tak menunjuk siapa pun, padahal yang menyetujui work order-nya
 * jelas orangnya. `null` diperbolehkan untuk sumber yang memang tak punya pelaku manusia.
 */
data class OrderFulfillmentCommand(
    val tenantId: UUID,
    val orderId: UUID,
    val transition: OrderTransition,
    val expectedRevision: Long? = null,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val actorId: UUID? = null,
)

data class OrderFulfillmentResult(
    val tenantId: UUID,
    val orderId: UUID,
    val status: String,
    val revision: Long,
    val replayed: Boolean,
)

/**
 * Pemesan adalah TEPAT SATU dari [customerId] (pelanggan terdaftar) atau [leadId] (calon
 * pelanggan). Sebelum V177 hanya ada [customerId] dan sifatnya wajib, yang memaksa setiap
 * penanya didaftarkan dulu sebagai pelanggan dan mengotori laporan langganan.
 */
data class CreateOrderCommand(
    val customerId: UUID?,
    val lines: List<OrderLineCommand>,
    val serviceAddress: ServiceAddress,
    val appointment: Appointment? = null,
    val operation: OperationCommand,
    val leadId: UUID? = null,
)

data class OrderLineCommand(val catalogItemId: UUID, val description: String, val quantity: Int)

data class ServiceAddress(
    val address: String,
    val city: String,
    val postalCode: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class Appointment(val startsAt: Instant, val endsAt: Instant)

data class OperationCommand(
    val namespace: String,
    val key: String,
    val payloadHash: String,
)

data class OrderTransitionCommand(
    val orderId: UUID,
    val transition: OrderTransition,
    val expectedRevision: Long,
    val reason: String? = null,
    val appointment: Appointment? = null,
    val operation: OperationCommand,
)

enum class OrderTransition { SUBMIT, ACCEPT, SCHEDULE, START_FULFILLING, FULFILL, CANCEL, REJECT }

/**
 * [leadId] dan [orderNumber] SENGAJA nullable meski di DB `order_number` NOT NULL: bentuk ini
 * juga dibekukan sebagai JSON di `order_operation.outcome_json` untuk replay idempotency, dan
 * baris yang ditulis sebelum V177 tak memuat kedua field itu. Kalau non-null, setiap replay
 * operation key lama akan gagal deserialisasi dan permintaan yang seharusnya aman diulang
 * malah meledak.
 */
data class OrderView(
    val id: UUID,
    val tenantId: UUID,
    val customerId: UUID?,
    val status: String,
    val lines: List<OrderLineView>,
    val serviceAddress: ServiceAddress,
    val appointment: Appointment?,
    val cancellationReason: String?,
    val rejectionReason: String?,
    val revision: Long,
    val lastActorId: UUID?,
    val lastOperationNamespace: String,
    val lastOperationKey: String,
    val lastOperationHash: String,
    val leadId: UUID? = null,
    val orderNumber: String? = null,
)

data class OrderLineView(val catalogItemId: UUID, val description: String, val quantity: Int)

/** Customer-safe contract. It intentionally has no technician, GPS, evidence, or approval data. */
data class PortalOrderView(
    val id: UUID,
    /** Yang dibacakan pelanggan saat menelepon; UUID tidak pernah dipakai manusia. */
    val orderNumber: String,
    val status: PortalOrderStatus,
    val lines: List<OrderLineView>,
    val serviceAddress: PortalServiceAddress,
    val appointment: Appointment?,
    val revision: Long,
)

data class PortalServiceAddress(
    val address: String,
    val city: String,
    val postalCode: String,
)

enum class PortalOrderStatus { RECEIVED, REVIEWING, SCHEDULED, IN_PROGRESS, WAITING_CUSTOMER, COMPLETED, CANCELLED, REQUIRES_ATTENTION }
