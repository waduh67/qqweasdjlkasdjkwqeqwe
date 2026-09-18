package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.LocationSnapshot
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.domain.model.LocationKind
import org.springframework.stereotype.Component

data class TransferLocations(val source: LocationSnapshot, val transit: LocationSnapshot, val destination: LocationSnapshot)

@Component
class WarehouseTransferAccess(private val scopes: InventoryWarehouseScopeApi, private val masters: WarehouseMasterStore,
    private val locations: WarehouseReceiptService, private val users: IamApi) {
    fun authorize(binding: WarehouseTransferDraft, current: CurrentAuthority): TransferLocations {
        masters.lockTopology()
        val scope = scopes.currentUnderFence(current.fence)
        val ids = listOf(binding.sourceLocationId, binding.transitLocationId, binding.destinationLocationId)
        if (ids.distinct().size != 3) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val found = ids.sortedBy { it.toString() }.associateWith { locations.authorizeLocation(it, current, scope) }
        val source = found.getValue(binding.sourceLocationId)
        val destination = found.getValue(binding.destinationLocationId)
        val transit = found.getValue(binding.transitLocationId)
        val supported = setOf(LocationKind.WAREHOUSE, LocationKind.BIN, LocationKind.VEHICLE, LocationKind.TECHNICIAN, LocationKind.QUARANTINE)
        if (source.kind !in supported || destination.kind !in supported || transit.kind != LocationKind.TRANSIT ||
            transit.issueEligible || transit.code == "RECEIPT_SOURCE") masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val receiver = users.findUser(binding.receiverId)?.takeIf { it.active }
            ?: masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
        if (destination.kind in setOf(LocationKind.TECHNICIAN, LocationKind.VEHICLE) && destination.custodianId != receiver.id)
            masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
        if (destination.kind == LocationKind.TECHNICIAN && !receiver.technician) masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
        return TransferLocations(source, transit, destination)
    }
}
