package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.provisioning.domain.model.VlanAllocationMode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "provisioning_vlan_allocation_scope")
class VlanAllocationScopeJpaEntity(
    id: UUID,
    @Column(name = "allocation_id", nullable = false, updatable = false) val allocationId: UUID,
    @Column(name = "pool_id", nullable = false, updatable = false) val poolId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    val mode: VlanAllocationMode,
    @Column(name = "pop_id", updatable = false) val popId: UUID?,
    @Column(name = "olt_id", updatable = false) val oltId: UUID?,
    @Column(name = "area_id", updatable = false) val areaId: UUID?,
    @Column(name = "service_class_id", updatable = false) val serviceClassId: UUID?,
    @Column(name = "intent_id", updatable = false) val intentId: UUID?,
) : TenantAwareJpaEntity(id)
