package com.duluin.ftth.monitoring.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.monitoring.domain.model.AlarmEntityType
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.monitoring.domain.model.AlarmSeverity
import com.duluin.ftth.monitoring.domain.model.AlarmStatus
import com.duluin.ftth.monitoring.domain.model.CollectorStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Collector TIDAK tenant-aware di sisi Hibernate.
 *
 * Barisnya dicari lewat hash API key sebelum tenant diketahui — kalau memakai
 * `@TenantId`, Hibernate akan menyaring dengan tenant sentinel dan autentikasi
 * collector tidak akan pernah berhasil. Tenant-nya justru DIAMBIL dari baris ini.
 * Sama persis dengan pola `refresh_token` di Phase 0.
 */
@Entity
@Table(name = "collector")
class CollectorJpaEntity(
    id: UUID,

    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID,

    @Column(nullable = false, length = 150)
    var name: String,

    @Column(name = "api_key_hash", nullable = false, length = 64, updatable = false)
    var apiKeyHash: String,

    @Column(name = "api_key_hint", nullable = false, length = 12, updatable = false)
    var apiKeyHint: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: CollectorStatus,

    @Column(name = "poll_interval_seconds", nullable = false)
    var pollIntervalSeconds: Int,

    @Column(name = "agent_version", length = 40)
    var agentVersion: String?,

    @Column(name = "last_seen_at")
    var lastSeenAt: Instant?,

    @Column(name = "last_cycle_summary")
    var lastCycleSummary: String?,
) : BaseJpaEntity(id)

@Entity
@Table(name = "alarm")
class AlarmJpaEntity(
    id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    var kind: AlarmKind,

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20, updatable = false)
    var entityType: AlarmEntityType,

    @Column(name = "entity_id", nullable = false, updatable = false)
    var entityId: UUID,

    @Column(name = "entity_label", nullable = false, length = 150)
    var entityLabel: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var severity: AlarmSeverity,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AlarmStatus,

    @Column(nullable = false, length = 500)
    var message: String,

    @Column(name = "measured_value")
    var measuredValue: Double?,

    @Column(name = "raised_at", nullable = false, updatable = false)
    var raisedAt: Instant,

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant,

    @Column(name = "cleared_at")
    var clearedAt: Instant?,

    @Column(name = "acknowledged_at")
    var acknowledgedAt: Instant?,

    @Column(name = "acknowledged_by")
    var acknowledgedBy: UUID?,

    @Column(name = "occurrence_count", nullable = false)
    var occurrenceCount: Int,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "alarm_rule")
class AlarmRuleJpaEntity(
    id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    var kind: AlarmKind,

    @Column(nullable = false)
    var enabled: Boolean,

    @Column(name = "warning_threshold")
    var warningThreshold: Double?,

    @Column(name = "critical_threshold")
    var criticalThreshold: Double?,

    @Column(name = "sustain_seconds", nullable = false)
    var sustainSeconds: Int,
) : TenantAwareJpaEntity(id)
