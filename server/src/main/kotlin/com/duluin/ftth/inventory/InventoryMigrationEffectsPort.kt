package com.duluin.ftth.inventory

import com.duluin.ftth.iam.CurrentAuthority
import java.util.UUID

interface InventoryMigrationEffectsPort {
    fun captureSources(cutover: TenantCutoverChangeFence, current: CurrentAuthority): List<ProvenanceSourceSnapshot>
}

interface InventoryProvenanceWorkOrderPort {
    fun authorize(ids: Set<UUID>, current: CurrentAuthority): Map<UUID, ProvenanceWorkOrderReference>
}

data class ProvenanceWorkOrderReference(val id: UUID, val code: String, val customerId: UUID?, val areaId: UUID?)
