package com.duluin.ftth.provisioning.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

data class SharedAllocationKey(
    val tenantId: UUID,
    val popId: UUID,
    val oltId: UUID,
    val areaId: UUID,
    val serviceClassId: UUID,
)

enum class VlanAllocationMode { SHARED, DEDICATED }

class VlanAllocationScope private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val allocationId: UUID,
    val poolId: UUID,
    val mode: VlanAllocationMode,
    val sharedKey: SharedAllocationKey?,
    val intentId: UUID?,
) : ProvisioningAggregate {
    init {
        val valid = when (mode) {
            VlanAllocationMode.SHARED -> sharedKey != null && sharedKey.tenantId == tenantId && intentId == null
            VlanAllocationMode.DEDICATED -> sharedKey == null && intentId != null
        }
        if (!valid) throw ValidationException("VLAN_ALLOCATION_SCOPE_INVALID")
    }

    companion object {
        fun shared(allocationId: UUID, poolId: UUID, key: SharedAllocationKey) = VlanAllocationScope(
            UuidV7.generate(),
            key.tenantId,
            allocationId,
            poolId,
            VlanAllocationMode.SHARED,
            key,
            null,
        )

        fun dedicated(tenantId: UUID, allocationId: UUID, poolId: UUID, intentId: UUID) = VlanAllocationScope(
            UuidV7.generate(),
            tenantId,
            allocationId,
            poolId,
            VlanAllocationMode.DEDICATED,
            null,
            intentId,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            allocationId: UUID,
            poolId: UUID,
            mode: VlanAllocationMode,
            sharedKey: SharedAllocationKey?,
            intentId: UUID?,
        ) = VlanAllocationScope(id, tenantId, allocationId, poolId, mode, sharedKey, intentId)
    }
}
