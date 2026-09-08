package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.service.WarehouseCommandService
import com.duluin.ftth.inventory.application.service.WarehouseMasterService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse")
class WarehouseMasterController(private val commands: WarehouseCommandService, private val masters: WarehouseMasterService) {
    private val mapper = JsonMapper.builder().findAndAddModules().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).build()

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
    fun detail(@PathVariable resource: String, @PathVariable id: UUID) = masters.get(kind(resource), id)

    @GetMapping("/{resource:skus|locations|suppliers}")
    fun list(@PathVariable resource: String, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int, @RequestParam(required = false) search: String?,
        @RequestParam(required = false) code: String?, @RequestParam(required = false) name: String?,
        @RequestParam(required = false) state: WarehouseMasterState?, @RequestParam(defaultValue = "code") sort: String,
        @RequestParam(defaultValue = "asc") direction: String) =
        masters.list(kind(resource), MasterFilter(page, size, search, code, name, state, sort, direction))

    @GetMapping("/assets/lookup")
    fun lookup(@RequestParam value: String) = masters.lookup(value)

    private fun command(resource: String, action: MasterAction, id: UUID?, body: String, key: String): ResponseEntity<String> {
        if (body.length > 16384) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val kind = kind(resource)
        val input = if (action == MasterAction.ARCHIVE) mapper.readValue(body, ArchiveMasterInput::class.java) else when (kind) {
            MasterKind.SKU -> mapper.readValue(body, SkuInput::class.java)
            MasterKind.LOCATION -> mapper.readValue(body, LocationInput::class.java)
            MasterKind.SUPPLIER -> mapper.readValue(body, SupplierInput::class.java)
        }
        val receipt = commands.executeMaster(kind, action, id, input, key)
        return ResponseEntity.status(receipt.originalStatus).contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
    }

    private fun kind(resource: String) = when (resource) {
        "skus" -> MasterKind.SKU
        "locations" -> MasterKind.LOCATION
        "suppliers" -> MasterKind.SUPPLIER
        else -> masterFailure(WarehouseErrorCode.NOT_FOUND)
    }
}
