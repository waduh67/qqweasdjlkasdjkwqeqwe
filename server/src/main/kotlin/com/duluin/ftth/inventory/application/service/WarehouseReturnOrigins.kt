package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.AssetRemovalStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialResidualStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseReturnStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.domain.model.OwnerKind
import org.springframework.stereotype.Component

@Component
class WarehouseReturnOrigins(private val residuals: MaterialResidualStore, private val removals: AssetRemovalStore,
    private val store: WarehouseReturnStore, private val locations: WarehouseReceiptService,
    private val scopes: InventoryWarehouseScopeApi) {
    fun resolve(request: WarehouseReturnIntake, current: CurrentAuthority): WarehouseReturnSource = when (request.origin) {
        WarehouseReturnOrigin.MATERIAL_RESIDUAL -> {
            val residual = residuals.get(request.sourceDocumentId)
            if (residual.purpose != ResidualPurpose.RETURN || !residuals.acknowledged(residual.id) ||
                residual.request.targetLocationId != request.quarantineLocationId)
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val source = store.position(residual.transit.copy(custodianKind = OwnerKind.WAREHOUSE,
                custodianId = request.quarantineLocationId))
            if (source.quantity.toString() != residual.request.quantityBase || source.unit != residual.request.baseUnit)
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            source
        }
        WarehouseReturnOrigin.ASSET_REMOVAL -> {
            val removal = removals.outcome(request.sourceDocumentId)
            if (removal.actorId == current.fence.identity.userId) masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
            removals.assertValid(request.sourceDocumentId)
            val source = store.recovered(request.sourceDocumentId)
            locations.authorizeLocation(source.dimension.locationId, current, scopes.currentUnderFence(current.fence))
            if (source.quantity != 1L || source.tracking != WarehouseTracking.SERIAL ||
                source.dimension.stockIdentityId != removal.result.assetId || source.dimension.legalOwner != removal.result.legalOwner)
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            source
        }
    }
}
