package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/returns")
class WarehouseReturnController(private val returns: InventoryReturnApi, private val repairs: InventoryReturnRepairApi,
    private val rma: InventoryCustomerRmaApi, private val titles: InventoryReturnReacquisitionApi,
    private val replacements: InventorySupplierReplacementApi, private val queries: InventoryReturnQueryApi) {
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

    @PostMapping("/{id}/reacquisition")
    fun reacquisition(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<ReturnReacquisitionRef> =
        ResponseEntity.status(201).body(titles.request(id, WarehouseReceiptJson.decode(body, ReturnReacquisitionInput::class.java), WarehouseMutationMetadata(key)))

    @PostMapping("/{id}/replacement-receipts")
    fun replacement(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<SupplierReplacementView> =
        ResponseEntity.status(201).body(replacements.request(id, WarehouseReceiptJson.decode(body, SupplierReplacementInput::class.java), WarehouseMutationMetadata(key)))

    @GetMapping("/{id}/replacement-receipts")
    fun replacements(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): List<SupplierReplacementView> =
        replacements.list(id, page(parameters))

    @GetMapping("/{id}") fun get(@PathVariable id: UUID): WarehouseReturnView = returns.get(id)
    @GetMapping("/{id}/details") fun details(@PathVariable id: UUID): WarehouseReturnDetails = queries.details(id)
    @GetMapping("/{id}/history") fun history(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): List<WarehouseReturnView> =
        returns.history(id, page(parameters))
    @GetMapping("/{id}/history/page") fun historyPage(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): WarehousePage<WarehouseReturnView> =
        queries.history(id, page(parameters))

    @GetMapping fun list(@RequestParam parameters: MultiValueMap<String, String>): WarehousePage<WarehouseReturnView> =
        returns.list(WarehouseReturnFilters.parse(parameters))
    @GetMapping("/workbench") fun workbench(@RequestParam parameters: MultiValueMap<String, String>): WarehousePage<WarehouseReturnDetails> =
        queries.list(WarehouseReturnFilters.parse(parameters))
    @GetMapping("/sources") fun sources(@RequestParam parameters: MultiValueMap<String, String>): WarehousePage<WarehouseReturnSourceOption> =
        queries.sources(WarehouseReturnFilters.parse(parameters, sources = true))

    private fun page(parameters: MultiValueMap<String, String>) = WarehouseReturnFilters.parse(parameters, history = true).let {
        WarehousePageRequest(it.page, it.size)
    }

    private fun response(receipt: WarehouseOperationReceipt): ResponseEntity<String> = ResponseEntity.status(receipt.originalStatus)
        .contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
}
