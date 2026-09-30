package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

/** Current display metadata is separate from immutable return operation replies. */
interface InventoryReturnQueryApi {
    fun list(filter: WarehouseReturnFilter): WarehousePage<WarehouseReturnDetails>
    fun details(id: UUID): WarehouseReturnDetails
    fun sources(filter: WarehouseReturnFilter): WarehousePage<WarehouseReturnSourceOption>
    fun history(id: UUID, page: WarehousePageRequest): WarehousePage<WarehouseReturnView>
}

data class WarehouseReturnDetails(val returnCase: WarehouseReturnView, val references: WarehouseReturnReferences)
data class WarehouseReturnNamedRef(val id: UUID, val code: String, val name: String?)
data class WarehouseReturnItemRef(val id: UUID, val code: String, val name: String, val tracking: WarehouseTracking,
    val serial: String?, val lotCode: String?)
data class WarehouseReturnAssetOriginRef(val assignmentId: UUID, val customerId: UUID, val workOrderId: UUID, val legalOwner: AssetLegalOwner)
data class WarehouseReturnReferences(val code: String, val sourceCode: String,
    val workOrderId: UUID?, val workOrderCode: String?, val item: WarehouseReturnItemRef,
    val locations: List<WarehouseReturnNamedRef>, val receivedByName: String?,
    val vendor: WarehouseReturnNamedRef?, val rmaHandoverId: UUID?, val assetOrigin: WarehouseReturnAssetOriginRef?)

/** Only current, whole, verified positions that have not been intaken appear here. */
data class WarehouseReturnSourceOption(val sourceDocumentId: UUID, val origin: WarehouseReturnOrigin,
    val code: String, val recordedAt: Instant, val workOrderId: UUID?, val workOrderCode: String?,
    val stockIdentityId: UUID, val lotId: UUID?, val item: WarehouseReturnItemRef,
    val quantityBase: String, val baseUnit: WarehouseBaseUnit, val legalOwner: AssetLegalOwner,
    val location: WarehouseReturnNamedRef, val quarantineLocationId: UUID?)
