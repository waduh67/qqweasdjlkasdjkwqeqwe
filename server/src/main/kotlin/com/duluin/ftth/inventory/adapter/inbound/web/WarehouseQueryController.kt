package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.application.service.WarehouseQueryService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse")
class WarehouseQueryController(private val queries: WarehouseQueryService) {
    @GetMapping("/stock")
    fun stock(@RequestParam parameters: MultiValueMap<String, String>) = response(queries.stock(parameters))

    @GetMapping("/stock/positions")
    fun positions(@RequestParam parameters: MultiValueMap<String, String>) = response(queries.positions(parameters))

    @GetMapping("/stock/positions/{id}")
    fun position(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(queries.positions(parameters, id))

    @GetMapping("/stock/unknown")
    fun unknown(@RequestParam parameters: MultiValueMap<String, String>) = response(queries.unknown(parameters))

    private fun response(body: String) = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Cache-Control", "no-store").body(body)
}
