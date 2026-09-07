package com.duluin.ftth.inventory.adapter.outbound.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.util.UUID

@Entity
@Immutable
@Table(name = "inventory_operation")
class WarehouseOperationJpaEntity(
    id: UUID,
    @Column(nullable = false) val namespace: String,
    @Column(nullable = false) val operationKey: String,
    @Column(nullable = false) val actorId: UUID,
    @Column(nullable = false) val resourceId: UUID,
    @Column(nullable = false, columnDefinition = "text") val resourceScope: String,
    @Column(nullable = false) val payloadHash: String,
    @Column(nullable = false) val documentId: UUID,
    @Column(nullable = false) val documentRevision: Long,
    @Column(nullable = false) val businessAction: String,
    @Column(nullable = false) val originalStatus: Int,
    @Column(nullable = false, columnDefinition = "text") val originalBody: String,
    @Column(nullable = false) val cutoverEpoch: Long,
    @Column(nullable = false) val authorityEpoch: Long,
) : WarehouseVersionedEntity(id)

@Entity
@Immutable
@Table(name = "inventory_outbox")
class WarehouseOutboxJpaEntity(
    id: UUID,
    @Column(nullable = false) val operationId: UUID,
    @Column(nullable = false) val documentId: UUID,
    @Column(nullable = false) val documentRevision: Long,
    @Column(nullable = false) val eventKind: String,
    @Column(nullable = false, columnDefinition = "text") val payload: String,
    @Column(nullable = false) val recordedAt: Instant,
) : WarehouseVersionedEntity(id)

@Entity
@Immutable
@Table(name = "inventory_inbox")
class WarehouseInboxJpaEntity(
    id: UUID,
    @Column(nullable = false) val eventId: UUID,
    @Column(nullable = false) val consumer: String,
    @Column(nullable = false) val operationId: UUID,
    @Column(nullable = false) val payloadHash: String,
    @Column(nullable = false) val recordedAt: Instant,
) : WarehouseVersionedEntity(id)

@Entity
@Immutable
@Table(name = "inventory_inspection")
class WarehouseInspectionJpaEntity(
    id: UUID,
    @Column(nullable = false) val documentLineId: UUID,
    @Column(nullable = false) val inspectorId: UUID,
    @Column(nullable = false) val acceptedBase: Long,
    @Column(nullable = false) val rejectedBase: Long,
    @Column(nullable = false) val baseUnit: String,
    @Column(nullable = false) val disposition: String,
    @Column(nullable = false) val evidenceReference: String,
    @Column(nullable = false) val operationId: UUID,
    val reason: String? = null,
) : WarehouseVersionedEntity(id)
