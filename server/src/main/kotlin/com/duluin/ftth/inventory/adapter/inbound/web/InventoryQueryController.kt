package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.application.service.*
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/inventory")
class InventoryQueryController(private val queries: InventoryApiService) {
    @GetMapping("/warehouses")
    @PreAuthorize("@authz.can('inventory.location.view')")
    fun warehouses(): List<InventoryLocationView> = queries.locations()

    @GetMapping("/items")
    @PreAuthorize("@authz.can('inventory.item.view')")
    fun items(): List<InventoryItemView> = queries.items()

    @GetMapping("/stock")
    @PreAuthorize("@authz.can('inventory.item.view')")
    fun stock(): List<InventoryStockView> = queries.stock()

    @GetMapping("/reservations")
    @PreAuthorize("@authz.can('inventory.custody.view')")
    fun reservations(): List<InventoryReservationView> = queries.reservations()

    @GetMapping("/custody")
    @PreAuthorize("@authz.can('inventory.custody.view')")
    fun custody(): List<InventoryCustodyView> = queries.custody()
}
