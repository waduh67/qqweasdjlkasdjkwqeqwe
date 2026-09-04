package com.duluin.ftth.order.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType
import java.util.UUID

interface OrderJpaRepository : JpaRepository<OrderJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForFulfillmentById(id: UUID): OrderJpaEntity?
    fun findAllByCustomerIdOrderById(customerId: UUID): List<OrderJpaEntity>
}
interface OrderLineJpaRepository : JpaRepository<OrderLineJpaEntity, UUID> {
    fun findAllByOrderIdOrderById(orderId: UUID): List<OrderLineJpaEntity>
    fun deleteAllByOrderId(orderId: UUID)
}
interface OrderOperationJpaRepository : JpaRepository<OrderOperationJpaEntity, UUID> {
    fun findByTenantIdAndNamespaceAndOperationKey(tenantId: UUID, namespace: String, operationKey: String): OrderOperationJpaEntity?
}
