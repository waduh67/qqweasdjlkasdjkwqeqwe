package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.type.LogicalType

internal object MaterialWorkflowJson {
    private val mapper = JsonMapper.builder().findAndAddModules().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .withCoercionConfig(LogicalType.Textual) { config ->
            listOf(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean).forEach { config.setCoercion(it, CoercionAction.Fail) }
        }.withCoercionConfig(LogicalType.Integer) { config ->
            listOf(CoercionInputShape.Float, CoercionInputShape.String, CoercionInputShape.EmptyString, CoercionInputShape.Boolean).forEach { config.setCoercion(it, CoercionAction.Fail) }
        }.enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).build()
    fun <T> decode(body: String, type: Class<T>): T {
        if (body.length > 131072) throw WarehouseContractException(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, "Request too large"))
        return mapper.readValue(body, type)
    }
}
