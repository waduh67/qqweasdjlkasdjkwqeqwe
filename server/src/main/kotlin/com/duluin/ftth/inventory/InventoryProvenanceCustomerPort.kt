package com.duluin.ftth.inventory

import com.duluin.ftth.iam.CurrentAuthority
import java.util.UUID

/** Customer owns current area authorization; inventory only holds preserved source references. */
interface InventoryProvenanceCustomerPort {
    fun authorize(customerIds: Set<UUID>, current: CurrentAuthority): Map<UUID, ProvenanceCustomerReference>
    fun captureSources(cutover: TenantCutoverChangeFence, current: CurrentAuthority): List<ProvenanceSourceSnapshot>
}

data class ProvenanceCustomerReference(val id: UUID, val name: String, val areaId: UUID?)
data class ProvenanceSourceSnapshot(val id: UUID, val sourceTable: String, val sourceId: UUID, val snapshotJson: String)
