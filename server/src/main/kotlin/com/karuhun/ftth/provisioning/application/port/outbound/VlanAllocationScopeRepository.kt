package com.karuhun.ftth.provisioning.application.port.outbound

import com.karuhun.ftth.provisioning.domain.model.SharedAllocationKey
import com.karuhun.ftth.provisioning.domain.model.VlanAllocationScope
import java.util.UUID

interface VlanAllocationScopeRepository {
    fun save(value: VlanAllocationScope): VlanAllocationScope
    fun findShared(key: SharedAllocationKey): VlanAllocationScope?
    fun findDedicated(tenantId: UUID, intentId: UUID): VlanAllocationScope?
    fun findByAllocationId(allocationId: UUID): VlanAllocationScope?
    fun delete(value: VlanAllocationScope)
}
