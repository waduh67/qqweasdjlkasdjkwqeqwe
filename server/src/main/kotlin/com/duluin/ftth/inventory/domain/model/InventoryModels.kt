package com.duluin.ftth.inventory.domain.model

import java.util.UUID

enum class InventoryStatus {
    AVAILABLE, RESERVED, ISSUED, IN_TRANSIT, CONSUMED, RETURNED, QUARANTINE, LOST, DISPOSED
}

enum class LocationKind { WAREHOUSE, BIN, VEHICLE, TECHNICIAN, CUSTOMER_SITE, QUARANTINE, LOST, DISPOSED, TRANSIT }

data class InventoryLocation(val id: UUID, val tenantId: UUID, val code: String, val kind: LocationKind) {
    init {
        require(code.trim().isNotEmpty()) { "location code is required" }
    }
}

data class Sku(val id: UUID, val tenantId: UUID, val code: String, val name: String) {
    init {
        require(code.matches(Regex("[A-Z0-9][A-Z0-9._-]{1,63}"))) { "invalid SKU" }
        require(name.trim().isNotEmpty()) { "item name is required" }
    }
}

data class Lot(val id: UUID, val tenantId: UUID, val skuId: UUID, val lotNumber: String, val quantity: Int) {
    init {
        require(lotNumber.trim().isNotEmpty()) { "lot number is required" }
        require(quantity >= 0) { "lot quantity cannot be negative" }
    }
}

data class CustodyClaim(
    val ownerId: UUID,
    val ownerKind: OwnerKind,
    val locationId: UUID?,
)

enum class OwnerKind { WAREHOUSE, VEHICLE, TECHNICIAN, CUSTOMER, REPAIR, TRANSIT, LOST, DISPOSED }

data class SerializedAsset(
    val id: UUID,
    val tenantId: UUID,
    val skuId: UUID,
    val serialNumber: String,
    val macAddress: String?,
    val status: InventoryStatus,
    val locationId: UUID,
    val custody: CustodyClaim,
    val installedOnuId: UUID? = null,
) {
    init {
        require(serialNumber.trim().isNotEmpty()) { "serial number is required" }
        require(macAddress == null || MAC.matches(macAddress)) { "invalid MAC address" }
        require(custody.locationId == locationId || custody.ownerKind == OwnerKind.TRANSIT) {
            "custody location must match asset location"
        }
        require(status != InventoryStatus.DISPOSED || custody.ownerKind == OwnerKind.DISPOSED) {
            "disposed asset must have disposed custody"
        }
    }

    fun transition(to: InventoryStatus, destination: InventoryLocation, nextCustody: CustodyClaim): SerializedAsset {
        require(destination.tenantId == tenantId) { "destination belongs to another tenant" }
        require(nextCustody.locationId == destination.id || nextCustody.ownerKind == OwnerKind.TRANSIT) {
            "custody does not claim destination"
        }
        require(isAllowed(status, to)) { "invalid inventory transition $status -> $to" }
        require(to != InventoryStatus.IN_TRANSIT || nextCustody.ownerKind == OwnerKind.TRANSIT) {
            "in-transit asset requires transit custody"
        }
        require(to != InventoryStatus.DISPOSED || nextCustody.ownerKind == OwnerKind.DISPOSED) {
            "disposed asset requires disposed custody"
        }
        return copy(status = to, locationId = destination.id, custody = nextCustody)
    }

    fun linkInstalledOnu(onuId: UUID): SerializedAsset {
        require(status == InventoryStatus.ISSUED || status == InventoryStatus.CONSUMED) {
            "only issued or consumed assets can be linked to an ONU"
        }
        require(installedOnuId == null || installedOnuId == onuId) { "asset already linked to another ONU" }
        return copy(installedOnuId = onuId)
    }

    companion object {
        private val MAC = Regex("(?i)^[0-9a-f]{2}([-:])[0-9a-f]{2}(\\1[0-9a-f]{2}){4}$")
        private fun isAllowed(from: InventoryStatus, to: InventoryStatus): Boolean = when (from) {
            InventoryStatus.AVAILABLE -> to in setOf(InventoryStatus.RESERVED, InventoryStatus.ISSUED, InventoryStatus.IN_TRANSIT, InventoryStatus.QUARANTINE, InventoryStatus.LOST)
            InventoryStatus.RESERVED -> to in setOf(InventoryStatus.AVAILABLE, InventoryStatus.ISSUED, InventoryStatus.IN_TRANSIT)
            InventoryStatus.ISSUED -> to in setOf(InventoryStatus.CONSUMED, InventoryStatus.RETURNED, InventoryStatus.QUARANTINE, InventoryStatus.LOST)
            InventoryStatus.IN_TRANSIT -> to in setOf(InventoryStatus.AVAILABLE, InventoryStatus.ISSUED, InventoryStatus.RETURNED, InventoryStatus.QUARANTINE, InventoryStatus.LOST)
            InventoryStatus.RETURNED -> to in setOf(InventoryStatus.AVAILABLE, InventoryStatus.QUARANTINE)
            InventoryStatus.QUARANTINE, InventoryStatus.LOST -> to == InventoryStatus.DISPOSED
            InventoryStatus.CONSUMED, InventoryStatus.DISPOSED -> false
        }
    }
}

class InventoryInvariantException(message: String) : IllegalArgumentException(message)
