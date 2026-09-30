package com.duluin.ftth.inventory

import java.util.UUID

interface InventoryReturnRepairApi {
    fun dispatch(id: UUID, request: WarehouseRepairDispatch, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun receive(id: UUID, request: WarehouseRepairReceipt, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
}

enum class WarehouseRepairResult { REPAIRED, UNREPAIRED }
data class WarehouseRepairDispatch(val expectedRevision: Long, val vendorId: UUID, val repairLocationId: UUID,
    val vendorReference: String, val evidenceReference: String, val observedSerial: String)
data class WarehouseRepairReceipt(val expectedRevision: Long, val observedSerial: String, val quarantineLocationId: UUID,
    val result: WarehouseRepairResult, val vendorReference: String, val evidenceReference: String)
data class WarehouseRepairProgress(val id: UUID, val vendorId: UUID, val vendorReference: String,
    val repairLocationId: UUID, val dispatchRevision: Long, val returnedRevision: Long? = null,
    val result: WarehouseRepairResult? = null, val receiptReference: String? = null)
