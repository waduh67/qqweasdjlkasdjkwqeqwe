package com.duluin.ftth.inventory.application.port.inbound

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.domain.model.LocationKind
import java.util.UUID

enum class MasterKind(val table: String, val permission: String) {
    SKU("inventory_sku", "inventory.sku"), LOCATION("inventory_location", "inventory.location"),
    SUPPLIER("inventory_supplier", "inventory.receipt")
}
enum class MasterAction { CREATE, UPDATE, ARCHIVE }

sealed interface MasterInput { val expectedRevision: Long? }
data class SkuInput(val code: String, val name: String, val tracking: WarehouseTracking,
    val baseUnit: WarehouseBaseUnit, val category: String? = null, val model: String? = null,
    val allowedOwnershipModes: Set<AssetOwnershipMode> = setOf(AssetOwnershipMode.LOAN, AssetOwnershipMode.SALE),
    val inspectionRequired: Boolean = true, val minimumQuantityBase: String = "0",
    override val expectedRevision: Long? = null) : MasterInput
data class SupplierInput(val code: String, val name: String, val contactReference: String? = null,
    override val expectedRevision: Long? = null) : MasterInput
data class LocationInput(val code: String, val name: String, val kind: LocationKind,
    val parentLocationId: UUID? = null, val siteId: UUID? = null, val areaId: UUID? = null,
    val custodianId: UUID? = null, val issueEligible: Boolean = false,
    override val expectedRevision: Long? = null) : MasterInput
data class ArchiveMasterInput(override val expectedRevision: Long) : MasterInput

sealed interface MasterSnapshot { val id: UUID; val revision: Long; val state: WarehouseMasterState }
data class SkuSnapshot(override val id: UUID, override val revision: Long, override val state: WarehouseMasterState,
    val code: String, val name: String, val tracking: WarehouseTracking, val baseUnit: WarehouseBaseUnit,
    val category: String?, val model: String?, val allowedOwnershipModes: Set<AssetOwnershipMode>,
    val inspectionRequired: Boolean, val minimumQuantityBase: String) : MasterSnapshot
data class SupplierSnapshot(override val id: UUID, override val revision: Long, override val state: WarehouseMasterState,
    val code: String, val name: String, val contactReference: String?) : MasterSnapshot
data class LocationSnapshot(override val id: UUID, override val revision: Long, override val state: WarehouseMasterState,
    val code: String, val name: String?, val kind: LocationKind, val parentLocationId: UUID?, val siteId: UUID?,
    val areaId: UUID?, val custodianId: UUID?, val issueEligible: Boolean) : MasterSnapshot
data class IdentityLookupSnapshot(val assetId: UUID, val skuId: UUID?, val serial: String,
    val mac: String?, val locationId: UUID, val legacyUnresolved: Boolean)
data class MasterFilter(val page: Int = 0, val size: Int = 25, val search: String? = null,
    val code: String? = null, val name: String? = null, val state: WarehouseMasterState? = null,
    val sort: String = "code", val direction: String = "asc")

fun masterFailure(code: WarehouseErrorCode, message: String = code.name): Nothing =
    throw WarehouseContractException(WarehouseError(code, message))
