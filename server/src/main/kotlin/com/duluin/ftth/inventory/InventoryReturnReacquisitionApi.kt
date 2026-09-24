package com.duluin.ftth.inventory

import java.util.UUID

interface InventoryReturnReacquisitionApi {
    fun request(id: UUID, input: ReturnReacquisitionInput, metadata: WarehouseMutationMetadata): ReturnReacquisitionRef
}

data class ReturnReacquisitionInput(val expectedRevision: Long, val reason: String,
    val titleTransferReference: String, val evidenceId: UUID)
data class ReturnReacquisitionRef(val documentId: UUID, val returnId: UUID, val revision: Long = 0)
