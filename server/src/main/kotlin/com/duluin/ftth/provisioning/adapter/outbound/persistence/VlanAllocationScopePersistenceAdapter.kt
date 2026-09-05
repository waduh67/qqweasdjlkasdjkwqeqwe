package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.VlanAllocationScopeRepository
import com.duluin.ftth.provisioning.domain.model.SharedAllocationKey
import com.duluin.ftth.provisioning.domain.model.VlanAllocationMode
import com.duluin.ftth.provisioning.domain.model.VlanAllocationScope
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class VlanAllocationScopePersistenceAdapter(
    private val jpa: VlanAllocationScopeJpaRepository,
) : VlanAllocationScopeRepository {
    override fun save(value: VlanAllocationScope): VlanAllocationScope {
        requireCurrentTenant(value.tenantId)
        val entity = jpa.findById(value.id).orElse(null) ?: VlanAllocationScopeJpaEntity(
            value.id,
            value.allocationId,
            value.poolId,
            value.mode,
            value.sharedKey?.popId,
            value.sharedKey?.oltId,
            value.sharedKey?.areaId,
            value.sharedKey?.serviceClassId,
            value.intentId,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findShared(key: SharedAllocationKey): VlanAllocationScope? {
        requireCurrentTenant(key.tenantId)
        return jpa.findByModeAndPopIdAndOltIdAndAreaIdAndServiceClassId(
            VlanAllocationMode.SHARED,
            key.popId,
            key.oltId,
            key.areaId,
            key.serviceClassId,
        )?.toDomain()
    }

    override fun findDedicated(tenantId: UUID, intentId: UUID): VlanAllocationScope? {
        requireCurrentTenant(tenantId)
        return jpa.findByModeAndIntentId(VlanAllocationMode.DEDICATED, intentId)?.toDomain()
    }

    override fun findByAllocationId(allocationId: UUID): VlanAllocationScope? =
        jpa.findByAllocationId(allocationId)?.toDomain()

    override fun delete(value: VlanAllocationScope) {
        requireCurrentTenant(value.tenantId)
        jpa.deleteById(value.id)
    }

    private fun VlanAllocationScopeJpaEntity.toDomain(): VlanAllocationScope {
        val tenant = tenantId ?: TenantContext.tenantId()
        val key = if (mode == VlanAllocationMode.SHARED) {
            SharedAllocationKey(
                tenant,
                requireNotNull(popId),
                requireNotNull(oltId),
                requireNotNull(areaId),
                requireNotNull(serviceClassId),
            )
        } else {
            null
        }
        return VlanAllocationScope.rehydrate(id, tenant, allocationId, poolId, mode, key, intentId)
    }

    private fun requireCurrentTenant(tenantId: UUID) {
        if (tenantId != TenantContext.tenantId()) throw ValidationException("ALLOCATION_TENANT_MISMATCH")
    }
}
