package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.service.ReferenceCatalogService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v2/warehouse")
class ReferenceCatalogController(private val service: ReferenceCatalogService) {
    @PostMapping("/{resource:skus|locations|suppliers}")
    fun create(@PathVariable resource: String, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        command(resource, MasterAction.CREATE, null, body, key)

    @PutMapping("/{resource:skus|locations|suppliers}/{id}")
    fun update(@PathVariable resource: String, @PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        command(resource, MasterAction.UPDATE, id, body, key)

    @PostMapping("/{resource:skus|locations|suppliers}/{id}/archive")
    fun archive(@PathVariable resource: String, @PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        command(resource, MasterAction.ARCHIVE, id, body, key)

    @GetMapping("/{resource:skus|locations|suppliers}/{id}")
    fun detail(@PathVariable resource: String, @PathVariable id: UUID) =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(service.get(kind(resource), id))

    @GetMapping("/{resource:skus|locations|suppliers}")
    fun list(@PathVariable resource: String, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int, @RequestParam(required = false) search: String?,
        @RequestParam(required = false) state: WarehouseMasterState?, @RequestParam(defaultValue = "code") sort: String,
        @RequestParam(defaultValue = "asc") direction: String) =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(service.list(kind(resource),
            MasterFilter(page = page, size = size, search = search, state = state, sort = sort, direction = direction)))

    private fun command(resource: String, action: MasterAction, id: UUID?, body: String, key: String): ResponseEntity<String> {
        val kind = kind(resource)
        val input = if (action == MasterAction.ARCHIVE) WarehouseReceiptJson.decode(body, ArchiveMasterInput::class.java) else when (kind) {
            MasterKind.SKU -> WarehouseReceiptJson.decode(body, SkuInput::class.java)
            MasterKind.LOCATION -> WarehouseReceiptJson.decode(body, LocationInput::class.java)
            MasterKind.SUPPLIER -> WarehouseReceiptJson.decode(body, SupplierInput::class.java)
        }
        val receipt = service.execute(kind, action, id, input, key)
        return ResponseEntity.status(receipt.originalStatus).header("Cache-Control", "no-store")
            .contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
    }

    private fun kind(resource: String) = when (resource) {
        "skus" -> MasterKind.SKU
        "locations" -> MasterKind.LOCATION
        "suppliers" -> MasterKind.SUPPLIER
        else -> masterFailure(WarehouseErrorCode.NOT_FOUND)
    }
}
