package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.WarehouseOperationReceipt
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.service.ReferenceWarehouseService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v2/warehouse")
class ReferenceWarehouseController(private val service: ReferenceWarehouseService) {
    @GetMapping("/workflow") fun workflow() = ResponseEntity.ok().header("Cache-Control", "no-store").body(service.workflow())
    @GetMapping("/my-materials") fun ownMaterials(@RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int, @RequestParam(required = false) search: String?) =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(service.ownMaterials(page, size, search))
    @PostMapping("/workflow/drain") fun drain(@RequestBody body: String) = service.drain(WarehouseReceiptJson.decode(body, ReferenceDrainInput::class.java))
    @GetMapping("/workflow/review") fun review() = json(service.review())
    @PostMapping("/workflow/activate") fun activate(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        json(service.activate(WarehouseReceiptJson.decode(body, ReferenceActivationInput::class.java), key))
    @PostMapping("/receipts") fun receipt(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.receive(WarehouseReceiptJson.decode(body, ReferenceReceiptInput::class.java), key))
    @PostMapping("/transfers") fun transfer(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.transfer(WarehouseReceiptJson.decode(body, ReferenceTransferInput::class.java), key))
    @GetMapping("/stock/{skuId}") fun stock(@PathVariable skuId: UUID) = ResponseEntity.ok().header("Cache-Control", "no-store").body(service.stock(skuId))
    @GetMapping("/stock/{skuId}/history") fun history(@PathVariable skuId: UUID, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int) =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(service.history(skuId, page, size))
    private fun json(body: String) = ResponseEntity.ok().header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON).body(body)
    private fun result(receipt: WarehouseOperationReceipt) = ResponseEntity.status(receipt.originalStatus)
        .header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
}
