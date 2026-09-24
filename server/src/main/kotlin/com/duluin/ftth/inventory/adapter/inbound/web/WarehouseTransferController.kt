package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.WarehouseTransferService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/transfers")
class WarehouseTransferController(private val transfers: WarehouseTransferService,
    private val discrepancies: com.duluin.ftth.inventory.application.service.WarehouseTransferDiscrepancyService,
    private val queries: InventoryTransferQueryApi) {
    @GetMapping
    fun list(@RequestParam parameters: MultiValueMap<String, String>): WarehousePage<WarehouseTransferDetails> =
        queries.list(WarehouseTransferFilters.parse(parameters))

    @GetMapping("/{id}/details") fun details(@PathVariable id: UUID): WarehouseTransferDetails = queries.details(id)

    @PostMapping
    fun create(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        response(transfers.create(WarehouseReceiptJson.decode(body, WarehouseTransferDraft::class.java), WarehouseMutationMetadata(key)))

    @PostMapping("/{id}/dispatch")
    fun dispatch(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        response(transfers.dispatch(id, WarehouseReceiptJson.decode(body, WarehouseTransferRevision::class.java), WarehouseMutationMetadata(key)))

    @PostMapping("/{id}/receive")
    fun receive(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        response(transfers.receive(id, WarehouseReceiptJson.decode(body, WarehouseTransferReceipt::class.java), WarehouseMutationMetadata(key)))

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        transfers.cancel(id, WarehouseReceiptJson.decode(body, WarehouseTransferRevision::class.java))

    @GetMapping("/{id}") fun get(@PathVariable id: UUID): WarehouseTransferView = transfers.get(id)
    @GetMapping("/{id}/discrepancy/recovery") fun recovery(@PathVariable id: UUID): ResponseEntity<WarehouseTransferDiscrepancyRecovery> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(discrepancies.recovery(id))
    @PostMapping("/{id}/discrepancy")
    fun discrepancy(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        response(discrepancies.report(id, WarehouseReceiptJson.decode(body, WarehouseTransferDiscrepancy::class.java), key))
    @GetMapping("/{id}/history") fun history(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): List<WarehouseTransferView> =
        transfers.history(id, historyPage(parameters))
    @GetMapping("/{id}/history/page") fun historyPage(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): WarehousePage<WarehouseTransferView> =
        queries.history(id, historyPage(parameters))

    private fun historyPage(parameters: MultiValueMap<String, String>): WarehousePageRequest =
        WarehouseTransferFilters.parse(parameters, history = true).let { WarehousePageRequest(it.page, it.size) }

    private fun response(receipt: WarehouseOperationReceipt): ResponseEntity<String> = ResponseEntity.status(receipt.originalStatus)
        .contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
}
