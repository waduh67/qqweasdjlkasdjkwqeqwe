package com.duluin.ftth.workorder.adapter.inbound.web

import com.duluin.ftth.inventory.WarehouseOperationReceipt
import com.duluin.ftth.inventory.adapter.inbound.web.WarehouseReceiptJson
import com.duluin.ftth.workorder.application.port.inbound.*
import com.duluin.ftth.workorder.application.service.ReferenceWorkIntakeService
import com.duluin.ftth.workorder.application.service.ReferenceWorkOrderService
import com.duluin.ftth.workorder.application.service.ReferenceWorkOrderCompletionService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v2/work-orders")
class ReferenceWorkOrderController(private val service: ReferenceWorkOrderService, private val completions: ReferenceWorkOrderCompletionService,
    private val intakes: ReferenceWorkIntakeService) {
    @GetMapping("/intake") fun intakeList(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int,
        @RequestParam(required = false) search: String?) = fresh(intakes.list(page, size, search))
    @GetMapping("/intake/{id}") fun intakeDetail(@PathVariable id: UUID) = fresh(intakes.detail(id))
    @PostMapping("/intake/{id}/dispatch") fun dispatch(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.dispatch(id, WarehouseReceiptJson.decode(body, ReferenceWorkDispatchInput::class.java), key))
    @GetMapping fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int,
        @RequestParam(required = false) state: ReferenceWorkOrderState?, @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "false") overdue: Boolean) = fresh(service.list(page, size, state, search, overdue))
    @GetMapping("/{id}") fun detail(@PathVariable id: UUID) = fresh(service.detail(id))
    @GetMapping("/types") fun types() = fresh(service.types())
    @PostMapping("/types") fun createType(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.saveType(null, WarehouseReceiptJson.decode(body, ReferenceWorkOrderTypeInput::class.java), key))
    @PutMapping("/types/{id}") fun updateType(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.saveType(id, WarehouseReceiptJson.decode(body, ReferenceWorkOrderTypeInput::class.java), key))
    @DeleteMapping("/types/{id}") fun deleteType(@PathVariable id: UUID, @RequestParam expectedRevision: Long,
        @RequestHeader("Idempotency-Key") key: String) = result(service.deleteType(id, expectedRevision, key))
    @PostMapping fun create(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.create(WarehouseReceiptJson.decode(body, ReferenceWorkOrderInput::class.java), key))
    @PutMapping("/{id}") fun update(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.update(id, WarehouseReceiptJson.decode(body, ReferenceWorkOrderUpdate::class.java), key))
    @PostMapping("/{id}/assignment") fun assign(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.assign(id, WarehouseReceiptJson.decode(body, ReferenceWorkOrderAssignment::class.java), key))
    @PostMapping("/{id}/progress") fun progress(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.progress(id, WarehouseReceiptJson.decode(body, ReferenceWorkOrderProgress::class.java), key))
    @PostMapping("/{id}/complete") fun complete(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(completions.complete(id, WarehouseReceiptJson.decode(body, ReferenceWorkOrderCompletionInput::class.java), key))
    private fun <T> fresh(body: T) = ResponseEntity.ok().header("Cache-Control", "no-store").body(body)
    private fun result(receipt: WarehouseOperationReceipt) = ResponseEntity.status(receipt.originalStatus)
        .header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
}
