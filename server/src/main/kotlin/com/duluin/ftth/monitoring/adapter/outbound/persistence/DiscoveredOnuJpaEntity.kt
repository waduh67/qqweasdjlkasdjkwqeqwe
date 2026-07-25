package com.duluin.ftth.monitoring.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnuState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Satu baris kotak masuk provisioning: ONU liar yang menunggu dituntaskan operator. */
@Entity
@Table(name = "discovered_onu")
class DiscoveredOnuJpaEntity(
    id: UUID,

    @Column(name = "serial_number", nullable = false, length = 64, updatable = false)
    var serialNumber: String,

    @Column(name = "olt_id")
    var oltId: UUID?,

    @Column(name = "olt_code", nullable = false, length = 64)
    var oltCode: String,

    @Column(name = "pon_port_label", length = 64)
    var ponPortLabel: String?,

    @Column(name = "last_status", nullable = false, length = 20)
    var lastStatus: String,

    @Column(name = "last_rx_power_dbm")
    var lastRxPowerDbm: Double?,

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    var firstSeenAt: Instant,

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant,

    @Column(name = "seen_count", nullable = false)
    var seenCount: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var state: DiscoveredOnuState,
) : TenantAwareJpaEntity(id)
