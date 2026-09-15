package com.duluin.ftth.customer

import com.duluin.ftth.inventory.CurrentAssetOwnership
import java.util.UUID

interface CustomerAssetOwnershipApi {
    fun current(customerId: UUID): List<CurrentAssetOwnership>
}
