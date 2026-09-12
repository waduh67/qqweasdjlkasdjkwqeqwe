package com.duluin.ftth.order.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.order.*
import com.duluin.ftth.order.application.port.inbound.SystemOrderUseCase
import com.duluin.ftth.order.application.port.outbound.OrderAuditEntry
import com.duluin.ftth.order.application.port.outbound.OrderAuditStore
import com.duluin.ftth.order.application.port.outbound.OrderCustomerProjection
import com.duluin.ftth.order.application.port.outbound.OrderNumberGenerator
import com.duluin.ftth.order.application.port.outbound.OrderOutboxStore
import com.duluin.ftth.order.application.port.outbound.OrderRepository
import com.duluin.ftth.order.domain.model.Order
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
@Suppress("LongParameterList")
class OrderApplicationService(
    private val orders: OrderRepository,
    private val currentUser: CurrentUserProvider,
    private val projection: OrderCustomerProjection,
    private val numbers: OrderNumberGenerator,
    private val audit: OrderAuditStore,
    private val outbox: OrderOutboxStore,
) : OrderApi, SystemOrderUseCase {

    @Transactional
    override fun create(command: CreateOrderCommand): OrderView {
        val user = currentUser.current()
        return create(user.tenantId, user.userId, command)
    }

    @Transactional
    override fun transition(command: OrderTransitionCommand): OrderView {
        val user = currentUser.current()
        return transition(user.tenantId, user.userId, command)
    }

    /**
     * Inti pembuatan pesanan, dengan tenant & pelaku DIBERIKAN pemanggil.
     *
     * Jalur ber-JWT di atas hanya membaca keduanya dari token lalu masuk ke sini; jalur publik
     * dan worker outbox memasoknya sendiri karena `SecurityContextHolder` di sana kosong.
     * Memisahkannya begini menjaga satu-satunya salinan aturan pembuatan pesanan tetap satu:
     * duplikat untuk "versi anonim" pasti akan menyimpang diam-diam saat salah satunya diubah.
     */
    @Transactional
    override fun create(tenantId: UUID, actorId: UUID?, command: CreateOrderCommand): OrderView =
        replayOrConflict(tenantId, command.operation) {
            val now = Instant.now()
            val order = Order.create(command, tenantId, actorId, numbers.next(tenantId, now))
            orders.save(order)
            record(order, previous = null, reason = null, eventType = ORDER_CREATED, at = now)
            outbox.enqueue(OrderCreated(order.id, order.tenantId, order.revision, actorId, command.operation, now))
            toView(order)
        }

    @Transactional
    override fun transition(tenantId: UUID, actorId: UUID?, command: OrderTransitionCommand): OrderView =
        replayOrConflict(tenantId, command.operation) {
            val order = orders.find(command.orderId) ?: throw NotFoundException("Order tidak ditemukan")
            if (order.tenantId != tenantId) throw NotFoundException("Order tidak ditemukan")
            val previous = order.status.name
            val now = Instant.now()
            order.transition(command, actorId)
            orders.save(order)
            record(order, previous, command.reason, ORDER_STATE_CHANGED, now)
            outbox.enqueue(
                OrderStateChanged(
                    order.id, order.tenantId, order.revision, previous, order.status.name,
                    actorId, command.operation, now, command.reason,
                ),
            )
            toView(order)
        }

    @Transactional(readOnly = true)
    override fun find(id: UUID): OrderView? {
        val user = currentUser.current()
        return orders.find(id)?.takeIf { it.tenantId == user.tenantId }?.let(::toView)
    }

    @Transactional(readOnly = true)
    override fun fulfillmentRevision(orderId: UUID): Long? = orders.findForFulfillment(orderId)?.revision

    @Transactional(readOnly = true)
    override fun portalOrders(customerId: UUID): List<PortalOrderView> =
        projection.findByCustomer(currentUser.current().tenantId, customerId)

    @Transactional(readOnly = true)
    override fun portalOrder(customerId: UUID, orderId: UUID): PortalOrderView? =
        projection.find(currentUser.current().tenantId, customerId, orderId)

    @Transactional
    override fun applyFulfillment(command: OrderFulfillmentCommand): OrderFulfillmentResult {
        require(command.transition == OrderTransition.START_FULFILLING || command.transition == OrderTransition.FULFILL) {
            "FULFILLMENT_ORDER_TRANSITION_NOT_ALLOWED"
        }
        /*
         * SENGAJA `currentOrNull`, bukan `current`.
         *
         * Efek fulfillment dijalankan oleh worker outbox di thread-nya sendiri — di sana
         * `SecurityContextHolder` kosong, dan `current()` melempar `IllegalStateException`
         * mentah. Dibungkus `runCatching` di saga, kegagalan itu muncul sebagai
         * `ORDER_EFFECT_REJECTED` yang menuntut rekonsiliasi manual, padahal tak ada yang
         * salah selain tak adanya token. Artinya efek `ORDER` TIDAK PERNAH bisa berhasil
         * lewat jalur asinkron sebelum ini.
         *
         * Kalau memang ada principal (jalur sinkron dari permintaan operator), tenant-nya
         * tetap WAJIB cocok: itu satu-satunya pemeriksaan yang bisa menangkap perintah
         * fulfillment yang menyeberang tenant.
         */
        val user = currentUser.currentOrNull()
        if (user != null && user.tenantId != command.tenantId) throw NotFoundException("Order tidak ditemukan")
        val actorId = command.actorId ?: user?.userId
        val operation = OperationCommand(command.namespace, command.operationKey, command.payloadHash)
        val replayed = orders.findOutcome(command.tenantId, command.namespace, command.operationKey) != null
        val view = replayOrConflict(command.tenantId, operation) {
            val order = orders.findForFulfillment(command.orderId) ?: throw NotFoundException("Order tidak ditemukan")
            if (order.tenantId != command.tenantId) throw NotFoundException("Order tidak ditemukan")
            val previous = order.status.name
            val now = Instant.now()
            order.transition(
                OrderTransitionCommand(
                    orderId = command.orderId,
                    transition = command.transition,
                    expectedRevision = command.expectedRevision ?: order.revision,
                    operation = operation,
                ),
                actorId,
            )
            orders.save(order)
            record(order, previous, reason = null, eventType = ORDER_STATE_CHANGED, at = now)
            outbox.enqueue(
                OrderStateChanged(
                    order.id, order.tenantId, order.revision, previous, order.status.name,
                    actorId, operation, now,
                ),
            )
            toView(order)
        }
        return OrderFulfillmentResult(command.tenantId, command.orderId, view.status, view.revision, replayed)
    }

    /**
     * Riwayat ditulis di transaksi yang SAMA dengan perubahan agregat. Kalau dipisah ke listener
     * after-commit, sebuah transisi yang berhasil tapi listener-nya gagal akan meninggalkan
     * pesanan tanpa jejak — dan justru transisi bermasalah itulah yang paling perlu terlacak.
     */
    private fun record(order: Order, previous: String?, reason: String?, eventType: String, at: Instant) {
        audit.append(
            OrderAuditEntry(
                tenantId = order.tenantId,
                orderId = order.id,
                revision = order.revision,
                eventType = eventType,
                fromStatus = previous,
                toStatus = order.status.name,
                reason = reason,
                actorId = order.lastActorId,
                operationNamespace = order.lastOperation.namespace,
                operationKey = order.lastOperation.key,
                payloadHash = order.lastOperation.payloadHash,
                occurredAt = at,
            ),
        )
    }

    private fun replayOrConflict(tenantId: UUID, operation: OperationCommand, effect: () -> OrderView): OrderView {
        require(operation.namespace.isNotBlank() && operation.key.isNotBlank() && operation.payloadHash.isNotBlank())
        val prior = orders.findOutcome(tenantId, operation.namespace, operation.key)
        if (prior != null) {
            if (prior.hash != operation.payloadHash) throw ConflictException("Operation key sudah dipakai untuk payload berbeda")
            return prior.value
        }
        val result = effect()
        orders.saveOutcome(tenantId, operation.namespace, operation.key, operation.payloadHash, result)
        return result
    }

    private fun toView(order: Order) = OrderView(
        order.id, order.tenantId, order.customerId, order.status.name,
        order.lines.map { OrderLineView(it.catalogItemId, it.description, it.quantity) }, order.serviceAddress,
        order.appointment, order.cancellationReason, order.rejectionReason, order.revision, order.lastActorId,
        order.lastOperation.namespace, order.lastOperation.key, order.lastOperation.payloadHash,
        order.leadId, order.orderNumber,
    )

    companion object {
        const val ORDER_CREATED = "ORDER_CREATED"
        const val ORDER_STATE_CHANGED = "ORDER_STATE_CHANGED"
    }
}
