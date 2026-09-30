package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

interface InventoryDispositionApi {
    fun request(input: WarehouseDispositionInput, metadata: WarehouseMutationMetadata): WarehouseDispositionView
    fun get(id: UUID): WarehouseDispositionView
    fun list(filter: WarehouseDispositionFilter): WarehousePage<WarehouseDispositionView>
}

enum class WarehouseDispositionAction { LOSS, SCRAP }
enum class WarehouseDispositionState { DRAFT, EXPIRED, REWORK_REQUIRED, POSTED }
data class WarehouseDispositionInput(val sourceDocumentId: UUID, val expectedRevision: Long,
    val stockIdentityId: UUID, val quantityBase: String, val baseUnit: WarehouseBaseUnit,
    val destinationLocationId: UUID, val action: WarehouseDispositionAction, val reason: String, val evidenceReference: String)
data class WarehouseDispositionView(val id: UUID, val code: String, val revision: Long, val state: WarehouseDispositionState,
    val action: WarehouseDispositionAction, val sourceDocumentId: UUID, val sourceRevision: Long,
    val stockIdentityId: UUID, val skuId: UUID, val quantityBase: String, val baseUnit: WarehouseBaseUnit,
    val sourceLocationId: UUID, val destinationLocationId: UUID, val legalOwner: AssetLegalOwner,
    val reason: String, val evidenceReference: String, val recordedAt: Instant,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val draftExpiry: WarehouseDraftExpiry? = null)
data class WarehouseDispositionFilter(val page: Int = 0, val size: Int = 25,
    val sourceDocumentId: UUID? = null, val action: WarehouseDispositionAction? = null)
