package com.duluin.ftth.customer

import com.duluin.ftth.inventory.CurrentAssetOwnership
import com.duluin.ftth.inventory.AssetExceptionContext
import java.util.UUID

interface CustomerAssetOwnershipApi {
    fun current(customerId: UUID): List<CurrentAssetOwnership>
    fun exceptionContext(customerId: UUID, assignmentId: UUID): AssetExceptionContext
}
