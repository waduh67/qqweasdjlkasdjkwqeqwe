package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

interface MaterialConsumptionApiV2 {
    fun forCustomer(customerId: UUID, page: WarehousePageRequest): WarehousePage<CustomerMaterialFactV2>
}

data class CustomerMaterialFactV2(
    val factId: UUID,
    val customerId: UUID,
    val workOrderId: UUID,
    val skuId: UUID,
    val stockIdentityId: UUID,
    val quantity: WarehouseQuantity,
    val useRevision: Long,
    val postingId: UUID,
    val compensatesFactId: UUID?,
    val recordedAt: Instant,
)
