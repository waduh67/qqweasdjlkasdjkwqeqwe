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

data class ProvenanceSourceAccess(val customers: Map<UUID, ProvenanceCustomerReference>,
    val workOrders: Map<UUID, ProvenanceWorkOrderReference>)

@Component
class WarehouseProvenanceAccess(private val authorities: CurrentAuthorityApi, private val scopes: InventoryWarehouseScopeApi,
    private val masters: WarehouseMasterStore, private val masterAccess: WarehouseMasterService,
    private val customers: InventoryProvenanceCustomerPort, private val workOrders: InventoryProvenanceWorkOrderPort,
    private val store: WarehouseProvenanceStore) {
    fun current(): CurrentAuthority = authorities.lockCurrent().also { receiptPermission(it, "inventory.provenance.manage") }

    /** Caller takes any batch lock before this topology/customer lock phase. */
    fun sources(current: CurrentAuthority): ProvenanceSourceAccess {
        current.fence.assertHeld()
        val orders = workOrders.authorize(store.workOrderIds(), current)
        masters.lockTopology()
        val scope = scopes.currentUnderFence(current.fence)
        val existing = if (current.platformAdmin) store.existingLocationIds() else emptySet()
        store.locationIds().forEach { id ->
            // Preserve orphan references for platform review without reading another tenant's location.
            // Stock proposals still require an actual active location belonging to this tenant.
            if (current.platformAdmin && id !in existing) return@forEach
            masterAccess.authorizeLocation(masters.get(MasterKind.LOCATION, id) as LocationSnapshot, current, scope)
        }
        return ProvenanceSourceAccess(customers.authorize(store.customerIds() + orders.values.mapNotNull { it.customerId }, current), orders)
    }
}
