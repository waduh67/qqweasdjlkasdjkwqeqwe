package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.application.port.outbound.VlanAllocationScopeRepository
import com.duluin.ftth.provisioning.application.port.outbound.VlanPoolRepository
import com.duluin.ftth.provisioning.domain.model.AllocationReference
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.SharedAllocationKey
import com.duluin.ftth.provisioning.domain.model.VlanAllocation
import com.duluin.ftth.provisioning.domain.model.VlanAllocationScope
import com.duluin.ftth.provisioning.domain.model.VlanPool
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class SharedVlanAllocationCommand(
    val poolId: UUID,
    val intentId: UUID,
    val key: SharedAllocationKey,
    val referenceId: UUID,
)

data class DedicatedVlanAllocationCommand(
    val tenantId: UUID,
    val poolId: UUID,
    val oltId: UUID,
    val intentId: UUID,
    val referenceId: UUID,
    val requestedVlanId: Int? = null,
)

@Service
@Transactional
class DeterministicVlanAllocationService(
    private val pools: VlanPoolRepository,
    private val scopes: VlanAllocationScopeRepository,
) {
    fun allocateShared(command: SharedVlanAllocationCommand): VlanAllocation {
        val pool = lockedPool(command.poolId, command.key.tenantId)
        val existingScope = scopes.findShared(command.key)
        if (existingScope != null) {
            if (existingScope.poolId != command.poolId) throw ConflictException("SHARED_ALLOCATION_POOL_MISMATCH")
            val allocation = activeAllocation(pool, existingScope.allocationId)
            addReferenceIfMissing(allocation, command.referenceId)
            return savedAllocation(pool, allocation.id)
        }

        val device = DeviceReference(DeviceKind.OLT, command.key.oltId)
        val activeVlans = pools.lockDeviceAndFindActiveVlans(command.key.tenantId, device)
        val vlanId = lowestFree(pool, activeVlans)
        val allocation = pool.allocate(device, vlanId, command.intentId)
        allocation.addReference(REFERENCE_KIND, command.referenceId)
        val saved = savedAllocation(pool, allocation.id)
        scopes.save(VlanAllocationScope.shared(saved.id, pool.id, command.key))
        return saved
    }

    fun allocateDedicated(command: DedicatedVlanAllocationCommand): VlanAllocation {
        val pool = lockedPool(command.poolId, command.tenantId)
        val existingScope = scopes.findDedicated(command.tenantId, command.intentId)
        if (existingScope != null) {
            if (existingScope.poolId != command.poolId) throw ConflictException("DEDICATED_ALLOCATION_POOL_MISMATCH")
            val allocation = activeAllocation(pool, existingScope.allocationId)
            if (command.requestedVlanId != null && command.requestedVlanId != allocation.vlanId) {
                throw ConflictException("DEDICATED_VLAN_MISMATCH")
            }
            addReferenceIfMissing(allocation, command.referenceId)
            return savedAllocation(pool, allocation.id)
        }

        val device = DeviceReference(DeviceKind.OLT, command.oltId)
        val activeVlans = pools.lockDeviceAndFindActiveVlans(command.tenantId, device)
        val vlanId = command.requestedVlanId ?: lowestFree(pool, activeVlans)
        if (vlanId in activeVlans) throw ConflictException("VLAN_ALREADY_ALLOCATED")
        val allocation = pool.allocate(device, vlanId, command.intentId)
        allocation.addReference(REFERENCE_KIND, command.referenceId)
        val saved = savedAllocation(pool, allocation.id)
        scopes.save(VlanAllocationScope.dedicated(command.tenantId, saved.id, pool.id, command.intentId))
        return saved
    }

    fun release(tenantId: UUID, allocationId: UUID, referenceId: UUID): VlanAllocation {
        val scope = scopes.findByAllocationId(allocationId)
            ?: throw NotFoundException("VLAN_ALLOCATION_SCOPE_NOT_FOUND")
        if (scope.tenantId != tenantId) throw ValidationException("ALLOCATION_TENANT_MISMATCH")
        val pool = lockedPool(scope.poolId, tenantId)
        val allocation = activeAllocation(pool, allocationId)
        pools.lockDeviceAndFindActiveVlans(tenantId, allocation.device)
        allocation.removeReference(REFERENCE_KIND, referenceId)
        if (allocation.referenceCount == 0) allocation.release()
        val saved = savedAllocation(pool, allocation.id)
        if (!saved.active) scopes.delete(scope)
        return saved
    }

    private fun lockedPool(poolId: UUID, tenantId: UUID): VlanPool {
        val pool = pools.findByIdForUpdate(poolId) ?: throw NotFoundException("VLAN_POOL_NOT_FOUND")
        if (pool.tenantId != tenantId) throw ValidationException("ALLOCATION_TENANT_MISMATCH")
        return pool
    }

    private fun lowestFree(pool: VlanPool, activeVlans: Set<Int>): Int =
        (pool.range.start..pool.range.endInclusive).firstOrNull { vlanId ->
            pool.reservedRanges.none { vlanId in it } &&
                vlanId !in activeVlans
        } ?: throw ConflictException("VLAN_POOL_EXHAUSTED")

    private fun activeAllocation(pool: VlanPool, allocationId: UUID): VlanAllocation =
        pool.allocations.singleOrNull { it.id == allocationId && it.active }
            ?: throw ConflictException("VLAN_ALLOCATION_SCOPE_STALE")

    private fun addReferenceIfMissing(allocation: VlanAllocation, referenceId: UUID) {
        val reference = AllocationReference(REFERENCE_KIND, referenceId)
        if (reference !in allocation.references) allocation.addReference(reference.kind, reference.referenceId)
    }

    private fun savedAllocation(pool: VlanPool, allocationId: UUID): VlanAllocation =
        pools.save(pool).allocations.single { it.id == allocationId }

    companion object {
        private const val REFERENCE_KIND = "SERVICE_INTENT"
    }
}
