package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

interface AssetLossCustomerPort {
    fun retire(closure: AssetLossClosure): UUID?
}

interface AssetLossProvisioningPort {
    fun enqueue(closure: AssetLossClosure, onuId: UUID?)
}

data class AssetLossClosure(val requestId: UUID, val operationId: UUID, val assignmentId: UUID,
    val assetId: UUID, val customerId: UUID, val workOrderId: UUID, val recordedAt: Instant)
