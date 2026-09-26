package com.duluin.ftth.inventory

import java.util.UUID
import java.time.Instant

interface InventoryReturnReacquisitionApi {
    fun request(id: UUID, input: ReturnReacquisitionInput, metadata: WarehouseMutationMetadata): ReturnReacquisitionRef
    fun list(id: UUID, page: WarehousePageRequest = WarehousePageRequest()): WarehousePage<ReturnReacquisitionEntry>
}

data class ReturnReacquisitionInput(val expectedRevision: Long, val reason: String,
    val titleTransferReference: String, val evidenceId: UUID)
data class ReturnReacquisitionRef(val documentId: UUID, val returnId: UUID, val revision: Long = 0)

data class ReturnReacquisitionEntry(val documentId: UUID, val returnId: UUID, val revision: Long,
    val code: String, val sourceReturnRevision: Long, val reason: String, val titleTransferReference: String,
    val evidenceId: UUID, val recordedAt: Instant, val appliedReturnRevision: Long?,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val state: String? = null,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val draftExpiry: WarehouseDraftExpiry? = null)
