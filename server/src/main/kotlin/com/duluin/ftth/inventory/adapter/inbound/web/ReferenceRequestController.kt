package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.WarehouseOperationReceipt
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.service.ReferenceRequestService
import com.duluin.ftth.inventory.application.service.ReferenceRequestMovementService
import com.duluin.ftth.inventory.application.service.ReferenceRequestStockService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v2/warehouse")
class ReferenceRequestController(private val service: ReferenceRequestService, private val movements: ReferenceRequestMovementService,
    private val stock: ReferenceRequestStockService) {
    @GetMapping("/requests/stock-preview") fun preview(@RequestParam skuId: UUID, @RequestParam(required = false) technicianId: UUID?,
        @RequestParam(required = false) warehouseId: UUID?, @RequestParam(required = false) requestId: UUID?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int) =
        fresh(stock.preview(skuId, technicianId, warehouseId, requestId, page, size))
    @GetMapping("/requests") fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int,
        @RequestParam(required = false) state: ReferenceRequestState?, @RequestParam(required = false) search: String?) =
        fresh(service.list(page, size, state, search))
    @GetMapping("/requests/{id}") fun detail(@PathVariable id: UUID) = fresh(service.detail(id))
    @PostMapping("/requests") fun submit(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.submit(WarehouseReceiptJson.decode(body, ReferenceRequestInput::class.java), key))
    @PostMapping("/requests/{id}/review") fun review(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.review(id, WarehouseReceiptJson.decode(body, ReferenceRequestReview::class.java), key))
    @PostMapping("/requests/{id}/decision") fun decide(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.decide(id, WarehouseReceiptJson.decode(body, ReferenceRequestDecision::class.java), key))
    @PostMapping("/requests/{id}/receipts") fun receive(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(movements.receive(id, WarehouseReceiptJson.decode(body, ReferenceRequestReceipt::class.java), key))
    @PostMapping("/requests/{id}/handovers") fun handover(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(movements.handover(id, WarehouseReceiptJson.decode(body, ReferenceRequestHandover::class.java), key))
    @GetMapping("/settings") fun settings() = fresh(service.settings())
    @PutMapping("/settings") fun settings(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.settings(WarehouseReceiptJson.decode(body, ReferenceOperationalSettingsInput::class.java), key))
    private fun <T> fresh(body: T) = ResponseEntity.ok().header("Cache-Control", "no-store").body(body)
    private fun result(receipt: WarehouseOperationReceipt) = ResponseEntity.status(receipt.originalStatus)
        .header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
}
