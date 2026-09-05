package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.provisioning.domain.model.VlanAllocationMode
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface VlanAllocationScopeJpaRepository : JpaRepository<VlanAllocationScopeJpaEntity, UUID> {
    fun findByModeAndPopIdAndOltIdAndAreaIdAndServiceClassId(
        mode: VlanAllocationMode,
        popId: UUID,
        oltId: UUID,
        areaId: UUID,
        serviceClassId: UUID,
    ): VlanAllocationScopeJpaEntity?

    fun findByModeAndIntentId(mode: VlanAllocationMode, intentId: UUID): VlanAllocationScopeJpaEntity?
    fun findByAllocationId(allocationId: UUID): VlanAllocationScopeJpaEntity?
}
