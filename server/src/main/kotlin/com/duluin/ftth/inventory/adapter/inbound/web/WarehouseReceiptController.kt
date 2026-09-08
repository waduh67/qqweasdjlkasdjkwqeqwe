package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.service.WarehouseCommandService
import com.duluin.ftth.inventory.application.service.WarehouseReceiptService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.type.LogicalType
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/receipts")
class WarehouseReceiptController(private val commands: WarehouseCommandService, private val receipts: WarehouseReceiptService) {
    private val mapper = JsonMapper.builder().findAndAddModules().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .withCoercionConfig(LogicalType.Textual) { config ->
            listOf(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean).forEach { config.setCoercion(it, CoercionAction.Fail) }
        }
        .withCoercionConfig(LogicalType.Integer) { config ->
            listOf(CoercionInputShape.Float, CoercionInputShape.String, CoercionInputShape.EmptyString, CoercionInputShape.Boolean).forEach { config.setCoercion(it, CoercionAction.Fail) }
        }.enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).build()

    @PostMapping
    fun create(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(commands.draftReceipt(null, decode(body, ReceiptDraftInput::class.java), key))

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(commands.draftReceipt(id, decode(body, ReceiptDraftInput::class.java), key))

    @GetMapping("/{id}") fun detail(@PathVariable id: UUID) = receipts.get(id)
    @GetMapping("/{id}/history") fun history(@PathVariable id: UUID) = receipts.history(id)
    @GetMapping
    fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int,
        @RequestParam(required = false) status: WarehouseReceiptState?, @RequestParam(required = false) skuId: UUID?,
        @RequestParam(required = false) serial: String?, @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) from: Instant?, @RequestParam(required = false) until: Instant?,
        @RequestParam(defaultValue = "createdAt") sort: String, @RequestParam(defaultValue = "desc") direction: String) =
        receipts.list(ReceiptFilter(page, size, status, skuId, serial, locationId, from, until, sort, direction))

    private fun <T> decode(body: String, type: Class<T>): T {
        if (body.length > 131072) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        return mapper.readValue(body, type)
    }
    private fun response(receipt: WarehouseOperationReceipt) = ResponseEntity.status(receipt.originalStatus)
        .contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
}
