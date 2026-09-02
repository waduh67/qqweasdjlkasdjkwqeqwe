package com.duluin.ftth.provisioning.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

data class VlanRange(val start: Int, val endInclusive: Int) {
    init {
        if (start !in 2..4094 || endInclusive !in 2..4094 || start > endInclusive) {
            throw ValidationException("VLAN_RANGE_INVALID: VLAN range must be within 2..4094")
        }
    }

    operator fun contains(vlanId: Int): Boolean = vlanId in start..endInclusive
}

class SegmentProfile private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val name: String,
    val poolId: UUID,
) : ProvisioningAggregate {
    companion object {
        fun create(tenantId: UUID, name: String, poolId: UUID): SegmentProfile {
            if (name.isBlank() || name.length > 120) throw ValidationException("SEGMENT_PROFILE_NAME_INVALID")
            return SegmentProfile(UuidV7.generate(), tenantId, name.trim(), poolId)
        }

        fun rehydrate(id: UUID, tenantId: UUID, name: String, poolId: UUID) =
            SegmentProfile(id, tenantId, name, poolId)
    }
}

class VlanPool private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val name: String,
    val range: VlanRange,
    val reservedRanges: List<VlanRange>,
    allocations: Collection<VlanAllocation>,
) : ProvisioningAggregate {
    private val mutableAllocations = allocations.toMutableList()
    val allocations: List<VlanAllocation> get() = mutableAllocations.toList()

    init {
        if (name.isBlank() || name.length > 120) throw ValidationException("VLAN_POOL_NAME_INVALID")
        if (reservedRanges.any { it.start !in range || it.endInclusive !in range }) {
            throw ValidationException("RESERVED_RANGE_OUTSIDE_POOL")
        }
        if (allocations.any { it.tenantId != tenantId || it.poolId != id }) {
            throw ValidationException("TENANT_OWNERSHIP_MISMATCH: allocation does not belong to pool")
        }
        if (allocations.filter(VlanAllocation::active).groupBy { it.device to it.vlanId }.any { it.value.size > 1 }) {
            throw ConflictException("VLAN_ALREADY_ALLOCATED")
        }
    }

    fun allocate(device: DeviceReference, vlanId: Int, intentId: UUID): VlanAllocation {
        validateVlan(vlanId)
        ActiveVlanAllocationPolicy.requireAvailable(tenantId, device, vlanId, mutableAllocations)
        return VlanAllocation.create(tenantId, id, device, vlanId, intentId).also(mutableAllocations::add)
    }

    private fun validateVlan(vlanId: Int) {
        if (vlanId !in 2..4094) throw ValidationException("VLAN_ID_OUT_OF_RANGE")
        if (vlanId !in range) throw ValidationException("VLAN_ID_OUTSIDE_POOL")
        if (reservedRanges.any { vlanId in it }) throw ValidationException("VLAN_ID_RESERVED")
    }

    companion object {
        fun create(
            tenantId: UUID,
            name: String,
            range: VlanRange,
            reservedRanges: List<VlanRange> = emptyList(),
        ) = VlanPool(UuidV7.generate(), tenantId, name.trim(), range, reservedRanges.toList(), emptyList())

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            name: String,
            range: VlanRange,
            reservedRanges: List<VlanRange>,
            allocations: Collection<VlanAllocation>,
        ) = VlanPool(id, tenantId, name, range, reservedRanges, allocations)
    }
}

class VlanAllocation private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val poolId: UUID,
    val device: DeviceReference,
    val vlanId: Int,
    val intentId: UUID,
    active: Boolean,
    references: Collection<AllocationReference>,
) : ProvisioningAggregate {
    var active: Boolean = active
        private set
    private val mutableReferences = references.toMutableSet()
    val references: Set<AllocationReference> get() = mutableReferences.toSet()
    val referenceCount: Int get() = mutableReferences.size

    fun addReference(kind: String, referenceId: UUID) {
        val reference = AllocationReference(kind.trim().uppercase(), referenceId)
        if (!mutableReferences.add(reference)) throw ConflictException("ALLOCATION_REFERENCE_ALREADY_EXISTS")
    }

    fun removeReference(kind: String, referenceId: UUID) {
        if (!mutableReferences.remove(AllocationReference(kind.trim().uppercase(), referenceId))) {
            throw ConflictException("ALLOCATION_REFERENCE_NOT_FOUND")
        }
    }

    fun release() {
        if (referenceCount != 0) throw ConflictException("ALLOCATION_STILL_REFERENCED")
        active = false
    }

    companion object {
        internal fun create(tenantId: UUID, poolId: UUID, device: DeviceReference, vlanId: Int, intentId: UUID) =
            VlanAllocation(UuidV7.generate(), tenantId, poolId, device, vlanId, intentId, true, emptyList())

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            poolId: UUID,
            device: DeviceReference,
            vlanId: Int,
            intentId: UUID,
            active: Boolean,
            references: Collection<AllocationReference>,
        ) = VlanAllocation(id, tenantId, poolId, device, vlanId, intentId, active, references)
    }
}

data class AllocationReference(val kind: String, val referenceId: UUID) {
    init {
        if (kind.isBlank() || kind.length > 40) throw ValidationException("ALLOCATION_REFERENCE_KIND_INVALID")
    }
}

object ActiveVlanAllocationPolicy {
    fun requireAvailable(
        tenantId: UUID,
        device: DeviceReference,
        vlanId: Int,
        existing: Collection<VlanAllocation>,
    ) {
        if (existing.any { it.tenantId == tenantId && it.active && it.device == device && it.vlanId == vlanId }) {
            throw ConflictException("VLAN_ALREADY_ALLOCATED")
        }
    }
}
