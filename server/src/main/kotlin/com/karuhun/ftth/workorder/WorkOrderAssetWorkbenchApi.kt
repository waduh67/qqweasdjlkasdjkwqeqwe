package com.karuhun.ftth.workorder

import com.karuhun.ftth.common.security.AuthorityFence
import com.karuhun.ftth.inventory.WarehousePage
import com.karuhun.ftth.inventory.WarehousePageRequest
import java.time.Instant
import java.util.UUID

interface WorkOrderAssetWorkbenchApi {
    fun jobs(customerId: UUID, page: WarehousePageRequest, authority: AuthorityFence): WarehousePage<AssetWorkOrderChoice>
    fun job(customerId: UUID, id: UUID, authority: AuthorityFence): AssetWorkOrderChoice
}
data class AssetWorkOrderChoice(val id: UUID, val code: String, val customerId: UUID, val workType: String,
    val status: String, val revision: Long, val signature: AssetWorkOrderSignature?)
data class AssetWorkOrderSignature(val id: UUID, val signerName: String, val recordedAt: Instant)
