package com.duluin.ftth.customer

import com.duluin.ftth.inventory.AssetRemovalResult
import java.util.UUID

interface CustomerAssetReplacementApi {
    fun applyRemoval(result: AssetRemovalResult, topology: CustomerAssetTopology?): CustomerAssetChange
}
data class CustomerAssetChange(val operationId: UUID, val retired: CustomerAssetEpisode, val replacement: CustomerAssetEpisode?)
data class ReplaceCustomerAssetRequest(val authorizationId: UUID, val expectedRevision: Long,
    val expectedAssignmentRevision: Long, val expectedTitleRevision: Long, val evidenceId: UUID, val topology: CustomerAssetTopology?)
