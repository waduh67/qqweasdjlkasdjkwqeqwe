package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/counts")
class WarehouseCountQueryController(private val queries: InventoryCountQueryApi) {
    @GetMapping("/workbench")
    fun list(@RequestParam parameters: MultiValueMap<String, String>) = privateResponse(queries.list(WarehouseCountFilters.parse(parameters)))
    @GetMapping("/positions")
    fun positions(@RequestParam parameters: MultiValueMap<String, String>) = privateResponse(queries.positions(WarehouseCountFilters.parse(parameters, "positions")))
    @GetMapping("/locations/{locationId}/counters")
    fun counters(@PathVariable locationId: UUID, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<WarehousePage<WarehouseCountPersonRef>> {
        val filter = WarehouseCountFilters.parse(parameters, "counters")
        return privateResponse(queries.counters(locationId, WarehousePageRequest(filter.page, filter.size), filter.query))
    }
    @GetMapping("/{id}/details") fun details(@PathVariable id: UUID) = privateResponse(queries.details(id))
    @GetMapping("/{id}/draft") fun draft(@PathVariable id: UUID) = privateResponse(queries.draft(id))
    @GetMapping("/{id}/review/details") fun review(@PathVariable id: UUID) = privateResponse(queries.review(id))
    @GetMapping("/{id}/history/page")
    fun history(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<WarehousePage<WarehouseCountHistoryEntry>> {
        val filter = WarehouseCountFilters.parse(parameters, "history")
        return privateResponse(queries.history(id, WarehousePageRequest(filter.page, filter.size)))
    }
    private fun <T : Any> privateResponse(value: T): ResponseEntity<T> = ResponseEntity.ok().header("Cache-Control", "no-store").body(value)
}
