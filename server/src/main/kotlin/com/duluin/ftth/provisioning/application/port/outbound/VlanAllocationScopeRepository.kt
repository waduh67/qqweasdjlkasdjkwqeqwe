package com.duluin.ftth.provisioning.application.port.outbound

import com.duluin.ftth.provisioning.domain.model.SharedAllocationKey
import com.duluin.ftth.provisioning.domain.model.VlanAllocationScope
import java.util.UUID

interface VlanAllocationScopeRepository {
    fun save(value: VlanAllocationScope): VlanAllocationScope
    fun findShared(key: SharedAllocationKey): VlanAllocationScope?
    fun findDedicated(tenantId: UUID, intentId: UUID): VlanAllocationScope?
    fun findByAllocationId(allocationId: UUID): VlanAllocationScope?
    fun delete(value: VlanAllocationScope)
}
