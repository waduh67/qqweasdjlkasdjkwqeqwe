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
    fun list(@RequestParam parameters: MultiValueMap<String, String>): WarehousePage<WarehouseTransferDetails> {
        fun invalid(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, "Invalid transfer filter"))
        if (parameters.any { (key, values) -> key !in setOf("page", "size", "state", "locationId", "query") || values.size != 1 || values.single().isBlank() }) invalid()
        fun number(key: String, default: Int): Int = parameters[key]?.single()?.let {
            if (!it.matches(Regex("[0-9]+"))) invalid()
            it.toIntOrNull() ?: invalid()
        } ?: default
        val state = parameters["state"]?.single()?.let { value -> WarehouseTransferState.entries.singleOrNull { it.name == value } ?: invalid() }
        val location = parameters["locationId"]?.single()?.let {
            val id = try { UUID.fromString(it) } catch (_: IllegalArgumentException) { invalid() }
            if (!id.toString().equals(it, ignoreCase = true)) invalid()
            id
        }
        return queries.list(WarehouseTransferFilter(number("page", 0), number("size", 25), state, location, parameters["query"]?.single()))
    }

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
    @PostMapping("/{id}/discrepancy")
    fun discrepancy(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        response(discrepancies.report(id, WarehouseReceiptJson.decode(body, WarehouseTransferDiscrepancy::class.java), key))
    @GetMapping("/{id}/history") fun history(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): List<WarehouseTransferView> =
        transfers.history(id, historyPage(parameters))
    @GetMapping("/{id}/history/page") fun historyPage(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): WarehousePage<WarehouseTransferView> =
        queries.history(id, historyPage(parameters))

    private fun historyPage(parameters: MultiValueMap<String, String>): WarehousePageRequest {
        fun invalid(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, "Invalid transfer history page"))
        if (parameters.any { (key, values) -> key !in setOf("page", "size") || values.size != 1 || values.single().isBlank() }) invalid()
        fun number(key: String, default: Int) = parameters[key]?.single()?.let {
            if (!it.matches(Regex("[0-9]+"))) invalid()
            it.toIntOrNull() ?: invalid()
        } ?: default
        return WarehousePageRequest(number("page", 0), number("size", 25))
    }

    private fun response(receipt: WarehouseOperationReceipt): ResponseEntity<String> = ResponseEntity.status(receipt.originalStatus)
        .contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
}
