package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.provisioning.domain.model.AdministrativeStatus
import com.duluin.ftth.provisioning.domain.model.InterfaceRole
import com.duluin.ftth.provisioning.domain.model.ManagedNodeRole
import com.duluin.ftth.provisioning.domain.model.TopologyReferenceKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "provisioning_managed_node")
class ManagedNodeJpaEntity(
    id: UUID,
    @Column(nullable = false, length = 120) var name: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var role: ManagedNodeRole,
    @Enumerated(EnumType.STRING) @Column(name = "reference_kind", length = 10) var referenceKind: TopologyReferenceKind?,
    @Column(name = "reference_id") var referenceId: UUID?,
    @Enumerated(EnumType.STRING)
    @Column(name = "administrative_status", nullable = false, length = 20)
    var administrativeStatus: AdministrativeStatus,
    @Column(name = "observed_at", nullable = false) var observedAt: Instant,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_managed_interface")
class ManagedInterfaceJpaEntity(
    id: UUID,
    @Column(name = "node_id", nullable = false, updatable = false) val nodeId: UUID,
    @Column(nullable = false, length = 120) var name: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var role: InterfaceRole,
    @Enumerated(EnumType.STRING) @Column(name = "reference_kind", length = 10) var referenceKind: TopologyReferenceKind?,
    @Column(name = "reference_id") var referenceId: UUID?,
    @Enumerated(EnumType.STRING)
    @Column(name = "administrative_status", nullable = false, length = 20)
    var administrativeStatus: AdministrativeStatus,
    @Column(name = "observed_at", nullable = false) var observedAt: Instant,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_transport_link")
class TransportLinkJpaEntity(
    id: UUID,
    @Column(name = "interface_a_id", nullable = false, updatable = false) val interfaceAId: UUID,
    @Column(name = "interface_z_id", nullable = false, updatable = false) val interfaceZId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "administrative_status", nullable = false, length = 20)
    var administrativeStatus: AdministrativeStatus,
    @Column(name = "observed_at", nullable = false) var observedAt: Instant,
) : TenantAwareJpaEntity(id)
