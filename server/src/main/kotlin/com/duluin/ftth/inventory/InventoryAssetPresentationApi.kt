package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

/** Portal callers supply their authenticated customer; this projection has no operational identifiers or costs. */
interface InventoryAssetPresentationApi {
    fun forCustomer(customerId: UUID, page: WarehousePageRequest): WarehousePage<CustomerAssetPresentation>
}
data class CustomerAssetPresentation(val deviceLabel: String, val serialNumber: String, val ownershipMode: AssetOwnershipMode,
    val legalOwner: AssetLegalOwner, val provenance: AssetProvenance, val installedAt: Instant, val removedAt: Instant?)
