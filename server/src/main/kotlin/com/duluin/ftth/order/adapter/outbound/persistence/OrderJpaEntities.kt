package com.duluin.ftth.order.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "order_record")
class OrderJpaEntity(
    id: UUID,
    @Column(name = "customer_id", nullable = false, updatable = false) var customerId: UUID,
    @Column(nullable = false, length = 24) var status: String,
    @Column(nullable = false) var revision: Long,
    @Column(name = "address_text", nullable = false) var address: String,
    @Column(nullable = false, length = 120) var city: String,
    @Column(name = "postal_code", nullable = false, length = 24) var postalCode: String,
    var latitude: Double?, var longitude: Double?,
    @Column(name = "appointment_starts_at") var appointmentStartsAt: Instant?,
    @Column(name = "appointment_ends_at") var appointmentEndsAt: Instant?,
    @Column(name = "cancellation_reason", length = 500) var cancellationReason: String?,
    @Column(name = "rejection_reason", length = 500) var rejectionReason: String?,
    @Column(name = "last_actor_id") var lastActorId: UUID?,
    @Column(name = "last_operation_namespace", nullable = false, length = 120) var lastOperationNamespace: String,
    @Column(name = "last_operation_key", nullable = false, length = 240) var lastOperationKey: String,
    @Column(name = "last_operation_hash", nullable = false, length = 128) var lastOperationHash: String,
    @Version @Column(name = "persistence_revision", nullable = false) var persistenceRevision: Long? = null,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "order_line")
class OrderLineJpaEntity(
    id: UUID,
    @Column(name = "order_id", nullable = false, updatable = false) var orderId: UUID,
    @Column(name = "catalog_item_id", nullable = false) var catalogItemId: UUID,
    @Column(nullable = false, length = 300) var description: String,
    @Column(nullable = false) var quantity: Int,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "order_operation")
class OrderOperationJpaEntity(
    id: UUID,
    @Column(name = "namespace", nullable = false, length = 120) var namespace: String,
    @Column(name = "operation_key", nullable = false, length = 240) var operationKey: String,
    @Column(name = "payload_hash", nullable = false, length = 128) var payloadHash: String,
    @Column(name = "outcome_json", nullable = false, columnDefinition = "text") var outcomeJson: String,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "order_outbox")
class OrderOutboxJpaEntity(
    id: UUID,
    @Column(name = "aggregate_id", nullable = false) var aggregateId: UUID,
    @Column(name = "event_type", nullable = false, length = 120) var eventType: String,
    @Column(nullable = false, columnDefinition = "text") var payload: String,
) : TenantAwareJpaEntity(id)
