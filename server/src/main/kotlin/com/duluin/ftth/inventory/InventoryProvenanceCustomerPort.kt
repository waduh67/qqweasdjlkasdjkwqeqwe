package com.duluin.ftth.inventory

import com.duluin.ftth.iam.CurrentAuthority
import java.util.UUID

/** Customer owns current area authorization; inventory only holds preserved source references. */
interface InventoryProvenanceCustomerPort {
    fun authorize(customerIds: Set<UUID>, current: CurrentAuthority): Map<UUID, ProvenanceCustomerReference>
}

data class ProvenanceCustomerReference(val id: UUID, val name: String, val areaId: UUID?)
