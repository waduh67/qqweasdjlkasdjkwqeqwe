package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/returns")
class WarehouseReturnController(private val returns: InventoryReturnApi, private val repairs: InventoryReturnRepairApi,
    private val rma: InventoryCustomerRmaApi) {
    @PostMapping
    fun receive(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        response(returns.receive(WarehouseReceiptJson.decode(body, WarehouseReturnIntake::class.java), WarehouseMutationMetadata(key)))

    @PostMapping("/{id}/inspect")
    fun inspect(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        response(returns.inspect(id, WarehouseReceiptJson.decode(body, WarehouseReturnInspection::class.java), WarehouseMutationMetadata(key)))

    @PostMapping("/{id}/repair-dispatch")
    fun repairDispatch(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        response(repairs.dispatch(id, WarehouseReceiptJson.decode(body, WarehouseRepairDispatch::class.java), WarehouseMutationMetadata(key)))

    @PostMapping("/{id}/repair-receive")
    fun repairReceive(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        response(repairs.receive(id, WarehouseReceiptJson.decode(body, WarehouseRepairReceipt::class.java), WarehouseMutationMetadata(key)))

    @PostMapping("/{id}/rma-handover")
    fun rmaHandover(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        response(rma.dispatch(id, WarehouseReceiptJson.decode(body, CustomerRmaDispatch::class.java), WarehouseMutationMetadata(key)))

    @GetMapping("/{id}") fun get(@PathVariable id: UUID): WarehouseReturnView = returns.get(id)
    @GetMapping("/{id}/history") fun history(@PathVariable id: UUID,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "100") size: Int): List<WarehouseReturnView> =
        returns.history(id, WarehousePageRequest(page, size))

    @GetMapping fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int,
        @RequestParam(required = false) origin: WarehouseReturnOrigin?, @RequestParam(required = false) state: WarehouseReturnState?,
        @RequestParam(required = false) locationId: UUID?, @RequestParam(required = false) skuId: UUID?,
        @RequestParam(required = false) stockIdentityId: UUID?, @RequestParam(required = false) owner: AssetLegalOwner?): WarehousePage<WarehouseReturnView> =
        returns.list(WarehouseReturnFilter(page, size, origin, state, locationId, skuId, stockIdentityId, owner))

    private fun response(receipt: WarehouseOperationReceipt): ResponseEntity<String> = ResponseEntity.status(receipt.originalStatus)
        .contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
}
