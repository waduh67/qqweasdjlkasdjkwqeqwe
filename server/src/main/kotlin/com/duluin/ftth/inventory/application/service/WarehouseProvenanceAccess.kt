package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseProvenanceStore
import com.duluin.ftth.inventory.application.port.inbound.LocationSnapshot
import com.duluin.ftth.inventory.application.port.inbound.MasterKind
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class WarehouseProvenanceAccess(private val authorities: CurrentAuthorityApi, private val scopes: InventoryWarehouseScopeApi,
    private val masters: WarehouseMasterStore, private val masterAccess: WarehouseMasterService,
    private val customers: InventoryProvenanceCustomerPort, private val store: WarehouseProvenanceStore) {
    fun current(): CurrentAuthority = authorities.lockCurrent().also { receiptPermission(it, "inventory.provenance.manage") }

    /** Caller takes any batch lock before this topology/customer lock phase. */
    fun sources(current: CurrentAuthority): Map<UUID, ProvenanceCustomerReference> {
        current.fence.assertHeld()
        masters.lockTopology()
        val scope = scopes.currentUnderFence(current.fence)
        store.locationIds().forEach { id ->
            masterAccess.authorizeLocation(masters.get(MasterKind.LOCATION, id) as LocationSnapshot, current, scope)
        }
        return customers.authorize(store.customerIds(), current)
    }
}
