package com.karuhun.ftth.inventory

import com.karuhun.ftth.iam.CurrentAuthority
import java.util.UUID

interface InventoryMigrationEffectsPort {
    fun cancelPending(requestId: UUID, operationId: UUID, cutover: TenantCutoverFence, current: CurrentAuthority)
    fun captureSources(cutover: TenantCutoverChangeFence, current: CurrentAuthority): List<ProvenanceSourceSnapshot>
}

interface InventoryProvenanceWorkOrderPort {
    fun authorize(ids: Set<UUID>, current: CurrentAuthority): Map<UUID, ProvenanceWorkOrderReference>
}

data class ProvenanceWorkOrderReference(val id: UUID, val code: String, val customerId: UUID?, val areaId: UUID?)
