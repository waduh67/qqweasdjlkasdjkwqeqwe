package com.karuhun.ftth.inventory.application.port.outbound

import com.karuhun.ftth.inventory.domain.model.CycleCount
import java.util.UUID

interface InventoryReconciliationRepository {
    fun save(count: CycleCount): CycleCount
    fun find(tenantId: UUID, countId: UUID): CycleCount?
    fun findOpen(tenantId: UUID): List<CycleCount>
}
