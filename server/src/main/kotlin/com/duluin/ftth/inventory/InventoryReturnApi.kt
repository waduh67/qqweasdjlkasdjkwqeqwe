package com.duluin.ftth.inventory

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

interface InventoryReturnApi {
    fun receive(request: WarehouseReturnIntake, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun inspect(id: UUID, request: WarehouseReturnInspection, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun get(id: UUID): WarehouseReturnView
    fun list(filter: WarehouseReturnFilter): WarehousePage<WarehouseReturnView>
    fun history(id: UUID, page: WarehousePageRequest = WarehousePageRequest()): List<WarehouseReturnView>
}

enum class WarehouseReturnOrigin { MATERIAL_RESIDUAL, ASSET_REMOVAL }

data class WarehouseReturnFilter(val page: Int = 0, val size: Int = 25, val origin: WarehouseReturnOrigin? = null,
    val state: WarehouseReturnState? = null, val locationId: UUID? = null, val skuId: UUID? = null,
    val stockIdentityId: UUID? = null, val owner: AssetLegalOwner? = null,
    val serial: String? = null, val query: String? = null, val from: Instant? = null, val until: Instant? = null)

data class WarehouseReturnIntake(val origin: WarehouseReturnOrigin, val sourceDocumentId: UUID,
    val quarantineLocationId: UUID, val evidenceReference: String)
data class WarehouseReturnInspection(val expectedRevision: Long, val measuredQuantityBase: String,
    val condition: WarehouseCondition, val destinationLocationId: UUID, val evidenceReference: String,
    val resetConfirmed: Boolean, val observedSerial: String? = null, val resetEvidenceReference: String? = null)
data class WarehouseReturnView(val id: UUID, val revision: Long, val state: WarehouseReturnState,
    val origin: WarehouseReturnOrigin, val sourceDocumentId: UUID, val stockIdentityId: UUID,
    val skuId: UUID, val lotId: UUID?, val baseUnit: WarehouseBaseUnit, val quantityBase: String,
    val locationId: UUID, val condition: WarehouseCondition, val legalOwner: AssetLegalOwner,
    val receivedBy: UUID, val recordedAt: Instant, val inspection: WarehouseReturnInspection? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val repair: WarehouseRepairProgress? = null)
