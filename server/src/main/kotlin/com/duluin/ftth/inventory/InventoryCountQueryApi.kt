package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

interface InventoryCountQueryApi {
    fun list(filter: WarehouseCountFilter): WarehousePage<WarehouseCountDetails>
    fun details(id: UUID): WarehouseCountDetails
    fun review(id: UUID): WarehouseCountReviewDetails
    fun positions(filter: WarehouseCountFilter): WarehousePage<WarehouseCountPositionOption>
    fun counters(locationId: UUID, page: WarehousePageRequest, query: String?): WarehousePage<WarehouseCountPersonRef>
    fun history(id: UUID, page: WarehousePageRequest): WarehousePage<WarehouseCountHistoryEntry>
}

data class WarehouseCountFilter(val page: Int = 0, val size: Int = 25, val state: WarehouseCountState? = null,
    val locationId: UUID? = null, val skuId: UUID? = null, val serial: String? = null, val query: String? = null,
    val from: Instant? = null, val until: Instant? = null)
data class WarehouseCountPersonRef(val id: UUID, val name: String?)
data class WarehouseCountLocationRef(val id: UUID, val code: String, val name: String?)
data class WarehouseCountItemRef(val skuId: UUID, val code: String, val name: String, val tracking: WarehouseTracking,
    val serial: String?, val lotCode: String?)
data class WarehouseCountLineRef(val balanceId: UUID, val item: WarehouseCountItemRef,
    val custodianId: UUID, val custodianKind: String, val condition: WarehouseCondition, val legalOwner: AssetLegalOwner)
data class WarehouseCountReferences(val code: String, val reason: String, val createdAt: Instant,
    val location: WarehouseCountLocationRef, val requester: WarehouseCountPersonRef, val counters: List<WarehouseCountPersonRef>,
    val lines: List<WarehouseCountLineRef>)
data class WarehouseCountDetails(val count: WarehouseCountView, val references: WarehouseCountReferences)
data class WarehouseCountReviewDetails(val review: WarehouseCountReview, val references: WarehouseCountReferences)
/** Intentionally excludes physical, reserved, expected and capacity quantities. */
data class WarehouseCountPositionOption(val id: UUID, val stockIdentityId: UUID, val item: WarehouseCountItemRef,
    val baseUnit: WarehouseBaseUnit, val location: WarehouseCountLocationRef, val custodianId: UUID,
    val custodianKind: String, val condition: WarehouseCondition, val legalOwner: AssetLegalOwner, val status: String)
data class WarehouseCountHistoryEntry(val fact: WarehouseCountFact, val recordedAt: Instant)
