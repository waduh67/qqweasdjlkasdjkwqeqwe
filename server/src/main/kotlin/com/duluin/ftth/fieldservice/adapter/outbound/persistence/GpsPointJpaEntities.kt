package com.duluin.ftth.fieldservice.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "fieldservice_gps_point")
class GpsPointJpaEntity(
    id: UUID,
    @Column(name = "visit_id", nullable = false, updatable = false) var visitId: UUID,
    @Column(name = "work_session_id", nullable = false, updatable = false) var workSessionId: UUID,
    @Column(name = "actor_id", nullable = false, updatable = false) var actorId: UUID,
    @Column(name = "device_id", nullable = false, updatable = false) var deviceId: UUID,
    @Column(nullable = false) var longitude: Double,
    @Column(nullable = false) var latitude: Double,
    @Column(name = "accuracy_meters", nullable = false) var accuracyMeters: Double,
    @Column(nullable = false, length = 40) var provider: String,
    @Column(name = "client_occurred_at", nullable = false) var clientOccurredAt: Instant,
    @Column(name = "server_received_at", nullable = false) var serverReceivedAt: Instant,
    @Column(name = "mock_indicator", nullable = false) var mockIndicator: Boolean,
    @Column(nullable = false, length = 24) var purpose: String,
    @Column(name = "retention_class", nullable = false, length = 32) var retentionClass: String,
    @Column(nullable = false, length = 24) var decision: String,
    @Column(nullable = false) var revision: Long,
    @Column(name = "operation_namespace", nullable = false, length = 120) var operationNamespace: String,
    @Column(name = "operation_key", nullable = false, length = 240) var operationKey: String,
    @Column(name = "payload_hash", nullable = false, length = 128) var payloadHash: String,
) : TenantAwareJpaEntity(id)
