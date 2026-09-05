package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

@Entity
@Table(name = "workorder_fulfillment_result")
class WorkOrderFulfillmentResultJpaEntity(
    id: UUID,
    @Column(name = "work_order_id", nullable = false, updatable = false) var workOrderId: UUID,
    @Column(nullable = false, updatable = false) var namespace: String,
    @Column(name = "operation_key", nullable = false, updatable = false) var operationKey: String,
    @Column(name = "payload_hash", nullable = false, updatable = false) var payloadHash: String,
    @Column(nullable = false, updatable = false) var source: String,
    @Column(nullable = false, updatable = false) var result: String,
) : TenantAwareJpaEntity(id)

interface WorkOrderFulfillmentResultJpaRepository : JpaRepository<WorkOrderFulfillmentResultJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByTenantIdAndNamespaceAndOperationKey(tenantId: UUID, namespace: String, key: String): WorkOrderFulfillmentResultJpaEntity?

    @Modifying
    @Query(value = "INSERT INTO workorder_fulfillment_result (id, tenant_id, work_order_id, namespace, operation_key, payload_hash, source, result) VALUES (:id, :tenantId, :workOrderId, :namespace, :operationKey, :payloadHash, :source, :result) ON CONFLICT (tenant_id, namespace, operation_key) DO NOTHING", nativeQuery = true)
    fun insertIfAbsent(id: UUID, tenantId: UUID, workOrderId: UUID, namespace: String, operationKey: String, payloadHash: String, source: String, result: String): Int
}
