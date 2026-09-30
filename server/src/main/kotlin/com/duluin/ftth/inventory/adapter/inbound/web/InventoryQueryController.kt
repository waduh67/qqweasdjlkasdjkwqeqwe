package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.application.service.WarehouseQueryService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/inventory")
class InventoryQueryController(private val warehouse: WarehouseQueryService) {
    @GetMapping("/warehouses")
    fun warehouses() = json(warehouse.legacyLocations())

    @GetMapping("/items")
    fun items() = json(warehouse.legacyItems())

    @GetMapping("/stock")
    fun stock() = org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .header("Cache-Control", "no-store").header("Link", "</api/v1/warehouse/stock>; rel=\"successor-version\"")
        .body(warehouse.legacyStock())

    @GetMapping("/reservations")
    fun reservations() = json(warehouse.legacyReservations())

    @GetMapping("/custody")
    fun custody() = json(warehouse.legacyCustody())

    private fun json(body: String) = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
        .header("Cache-Control", "no-store").body(body)
}
