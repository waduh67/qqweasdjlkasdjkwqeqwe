package com.duluin.ftth.inventory

import java.util.UUID

interface InventoryIssueApi {
    fun pick(context: MaterialPlanningContext, request: WarehousePickRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun transition(context: MaterialPlanningContext, request: WarehouseIssueRequest, metadata: WarehouseMutationMetadata, dispatch: Boolean): WarehouseOperationReceipt
    fun slip(context: MaterialPlanningContext, issueId: UUID): String
}

data class WarehousePickRequest(val expectedRevision: Long, val workOrderRevision: Long, val demandRevision: Long,
    val lines: List<WarehousePickSelection>)
data class WarehousePickSelection(val reservationId: UUID, val expectedRevision: Long, val stockIdentityId: UUID,
    val stockRevision: Long, val quantityBase: String, val baseUnit: WarehouseBaseUnit, val scan: String? = null)
data class WarehouseIssueRequest(val issueId: UUID, val expectedRevision: Long, val workOrderRevision: Long,
    val planRevision: Long, val demandRevision: Long, val partial: Boolean = false, val reason: String)
