package com.duluin.ftth.incident.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.incident.domain.model.IncidentEventType
import com.duluin.ftth.incident.domain.model.IncidentRootType
import com.duluin.ftth.incident.domain.model.IncidentSeverity
import com.duluin.ftth.incident.domain.model.IncidentStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "incident")
class IncidentJpaEntity(
    id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "root_type", nullable = false, length = 20, updatable = false)
    var rootType: IncidentRootType,

    @Column(name = "root_id", nullable = false, updatable = false)
    var rootId: UUID,

    @Column(name = "root_label", nullable = false, length = 150)
    var rootLabel: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var severity: IncidentSeverity,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: IncidentStatus,

    @Column(nullable = false, length = 300)
    var title: String,

    @Column(name = "alarm_count", nullable = false)
    var alarmCount: Int,

    @Column(name = "affected_customer_count", nullable = false)
    var affectedCustomerCount: Int,

    @Column(name = "opened_at", nullable = false, updatable = false)
    var openedAt: Instant,

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant,

    @Column(name = "acknowledged_at")
    var acknowledgedAt: Instant?,

    @Column(name = "acknowledged_by")
    var acknowledgedBy: UUID?,

    @Column(name = "resolved_at")
    var resolvedAt: Instant?,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "incident_event")
class IncidentEventJpaEntity(
    id: UUID,

    @Column(name = "incident_id", nullable = false, updatable = false)
    var incidentId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    var type: IncidentEventType,

    @Column(nullable = false, length = 500, updatable = false)
    var message: String,

    @Column(name = "actor_id", updatable = false)
    var actorId: UUID?,

    @Column(nullable = false, updatable = false)
    var at: Instant,
) : TenantAwareJpaEntity(id)
