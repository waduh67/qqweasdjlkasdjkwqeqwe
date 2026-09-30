package com.duluin.ftth.customer

import com.duluin.ftth.inventory.WarehouseMutationMetadata
import java.util.UUID

interface CustomerAssetRelocationApi {
    fun relocate(context: CustomerAssetRelocationContext, request: CustomerAssetRelocationRequest, metadata: WarehouseMutationMetadata): CustomerAssetRelocation
}
data class CustomerAssetRelocationContext(val customerId: UUID, val assignmentId: UUID)
data class CustomerAssetRelocationRequest(val workOrderId: UUID, val expectedWorkOrderRevision: Long, val expectedRevision: Long,
    val topology: CustomerAssetTopology)
data class CustomerAssetRelocation(val operationId: UUID, val onuId: UUID, val revision: Long, val topology: CustomerAssetTopology,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val episodeRevision: Long? = null)
