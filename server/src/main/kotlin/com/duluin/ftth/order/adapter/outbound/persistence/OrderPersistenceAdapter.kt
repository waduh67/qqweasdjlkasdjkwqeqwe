package com.duluin.ftth.order.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.order.*
import com.duluin.ftth.order.application.port.outbound.*
import com.duluin.ftth.order.domain.model.*
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
class OrderPersistenceAdapter(
    private val orders: OrderJpaRepository,
    private val lines: OrderLineJpaRepository,
    private val operations: OrderOperationJpaRepository,
    private val mapper: ObjectMapper,
) : OrderRepository {
    override fun save(order: Order) {
        val entity = orders.findById(order.id).orElse(null)?.apply { copyFrom(order) }
            ?: OrderJpaEntity(order.id, order.customerId, order.status.name, order.revision, order.serviceAddress.address,
                order.serviceAddress.city, order.serviceAddress.postalCode, order.serviceAddress.latitude, order.serviceAddress.longitude,
                order.appointment?.startsAt, order.appointment?.endsAt, order.cancellationReason, order.rejectionReason, order.lastActorId,
                order.lastOperation.namespace, order.lastOperation.key, order.lastOperation.payloadHash)
        orders.save(entity)
        lines.deleteAllByOrderId(order.id)
        lines.saveAll(order.lines.map { OrderLineJpaEntity(UUID.randomUUID(), order.id, it.catalogItemId, it.description, it.quantity) })
    }
    override fun find(id: UUID): Order? = orders.findById(id).orElse(null)?.toDomain()
    override fun findForFulfillment(id: UUID): Order? = orders.findForFulfillmentById(id)?.toDomain()
    override fun findByCustomer(customerId: UUID): List<Order> = orders.findAllByCustomerIdOrderById(customerId).map { it.toDomain() }
    override fun findOutcome(tenantId: UUID, namespace: String, key: String): StoredOrderOutcome? =
        operations.findByTenantIdAndNamespaceAndOperationKey(tenantId, namespace, key)?.let {
            StoredOrderOutcome(it.payloadHash, mapper.readValue(it.outcomeJson, OrderView::class.java))
        }
    override fun saveOutcome(tenantId: UUID, namespace: String, key: String, hash: String, value: OrderView) {
        try {
            operations.save(OrderOperationJpaEntity(UUID.randomUUID(), namespace, key, hash, mapper.writeValueAsString(value)))
        } catch (_: DataIntegrityViolationException) {
            val prior = findOutcome(tenantId, namespace, key)
            if (prior == null || prior.hash != hash) throw IllegalStateException("Operation outcome conflicted concurrently")
        }
    }
    private fun OrderJpaEntity.copyFrom(o: Order) { status=o.status.name; revision=o.revision; address=o.serviceAddress.address; city=o.serviceAddress.city; postalCode=o.serviceAddress.postalCode; latitude=o.serviceAddress.latitude; longitude=o.serviceAddress.longitude; appointmentStartsAt=o.appointment?.startsAt; appointmentEndsAt=o.appointment?.endsAt; cancellationReason=o.cancellationReason; rejectionReason=o.rejectionReason; lastActorId=o.lastActorId; lastOperationNamespace=o.lastOperation.namespace; lastOperationKey=o.lastOperation.key; lastOperationHash=o.lastOperation.payloadHash }
    private fun OrderJpaEntity.toDomain() = Order.rehydrate(id, tenantId ?: TenantContext.tenantId(), customerId,
        lines.findAllByOrderIdOrderById(id).map { OrderLineCommand(it.catalogItemId, it.description, it.quantity) },
        ServiceAddress(address, city, postalCode, latitude, longitude), appointmentStartsAt?.let { Appointment(it, requireNotNull(appointmentEndsAt)) },
        OrderStatus.valueOf(status), cancellationReason, rejectionReason, revision, lastActorId,
        OperationCommand(lastOperationNamespace, lastOperationKey, lastOperationHash))
}
