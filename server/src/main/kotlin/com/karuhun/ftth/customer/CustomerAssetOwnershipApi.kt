package com.karuhun.ftth.customer

import com.karuhun.ftth.inventory.CurrentAssetOwnership
import com.karuhun.ftth.inventory.AssetExceptionContext
import java.util.UUID

interface CustomerAssetOwnershipApi {
    fun current(customerId: UUID): List<CurrentAssetOwnership>
    fun exceptionContext(customerId: UUID, assignmentId: UUID): AssetExceptionContext
}
