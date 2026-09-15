package com.duluin.ftth.fulfillment

import java.util.UUID

fun interface AssetProvisioningPort {
    fun apply(work: AssetProvisioningWork): AssetProvisioningOutcome
}
data class AssetProvisioningWork(val tenantId: UUID, val operationId: UUID, val customerId: UUID,
    val workOrderId: UUID, val oldOnuId: UUID?, val newOnuId: UUID?)
sealed interface AssetProvisioningOutcome {
    data object Succeeded : AssetProvisioningOutcome
    data class ReconciliationRequired(val code: String) : AssetProvisioningOutcome
}
data class AssetProvisioningClaim(val work: AssetProvisioningWork, val token: UUID)
