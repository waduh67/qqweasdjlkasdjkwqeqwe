package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.service.ReferenceCountService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v2/warehouse/counts")
class ReferenceCountController(private val service: ReferenceCountService) {
    @PostMapping("/snapshot") fun load(@RequestBody body: String) = fresh(200, service.load(WarehouseReceiptJson.decode(body, ReferenceCountLoad::class.java)))
    @PostMapping fun save(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        fresh(201, service.save(WarehouseReceiptJson.decode(body, ReferenceCountInput::class.java), key))
    @GetMapping fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int) = fresh(200, service.list(page, size))
    @GetMapping("/locations") fun locations(@RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int) = fresh(200, service.locations(search, page, size))
    @GetMapping("/{id}") fun detail(@PathVariable id: UUID) = fresh(200, service.detail(id))
    private fun <T> fresh(status: Int, body: T) = ResponseEntity.status(status).header("Cache-Control", "no-store").body(body)
}
