package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.domain.model.LocationKind
import com.duluin.ftth.iam.AreaReferenceApi
import com.duluin.ftth.iam.CurrentAuthorityApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class ReferenceCatalogService(private val masters: WarehouseMasterService, private val authority: CurrentAuthorityApi,
    private val areas: AreaReferenceApi) {
    fun areas() = authority.lockCurrent().let { current ->
        if (!current.platformAdmin && "warehouse.catalog.view" !in current.permissions) masterFailure(WarehouseErrorCode.FORBIDDEN)
        areas.areasInScope(current.areaScope)
    }

    fun execute(kind: MasterKind, action: MasterAction, id: UUID?, input: MasterInput, key: String): WarehouseOperationReceipt {
        if (input is LocationInput && input.kind !in setOf(LocationKind.WAREHOUSE, LocationKind.BIN))
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Pilih gudang atau rak gudang")
        return masters.executeReference(kind, action, id, input, key)
    }

    fun get(kind: MasterKind, id: UUID): MasterSnapshot = masters.getReference(kind, id)
    fun list(kind: MasterKind, filter: MasterFilter): WarehousePage<MasterSnapshot> = masters.listReference(kind, filter)
}
