package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.inventory.domain.model.CycleCount
import java.util.UUID

/** Stock opname dan selisihnya (`inventory_cycle_count`, V147). */
interface InventoryReconciliationRepository {
    fun save(count: CycleCount): CycleCount

    /**
     * Cari tanpa menyebut tenant — RLS dan `@TenantId` Hibernate yang membatasi
     * jangkauannya ke tenant aktif, jadi id milik tenant lain tidak akan pernah ketemu.
     */
    fun findById(countId: UUID): CycleCount?

    fun find(tenantId: UUID, countId: UUID): CycleCount?

    /** Replay stock opname yang kuncinya sudah dipakai; pembanding payload hash ada di service. */
    fun findByOperation(tenantId: UUID, operationKey: String): CycleCount?

    fun findOpen(tenantId: UUID): List<CycleCount>
}
