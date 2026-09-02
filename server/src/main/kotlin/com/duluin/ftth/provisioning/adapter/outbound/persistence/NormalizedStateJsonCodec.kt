package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import com.duluin.ftth.provisioning.domain.model.NormalizedValue
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

@Component
class NormalizedStateJsonCodec(private val objectMapper: ObjectMapper) {
    fun encode(state: NormalizedDeviceState): JsonNode = objectMapper.createObjectNode().also { root ->
        state.values.forEach { (field, value) -> root.set(field.wireName, encodeValue(value)) }
    }

    fun decode(node: JsonNode): NormalizedDeviceState {
        if (!node.isObject) throw ValidationException("NORMALIZED_STATE_OBJECT_REQUIRED")
        val values = node.properties().associate { (key, value) ->
            NormalizedField.fromWireName(key) to decodeValue(value)
        }
        return NormalizedDeviceState.from(values)
    }

    private fun encodeValue(value: NormalizedValue): JsonNode = when (value) {
        is NormalizedValue.Identifier -> objectMapper.nodeFactory.stringNode(value.value)
        is NormalizedValue.Number -> objectMapper.nodeFactory.numberNode(value.value)
        is NormalizedValue.Flag -> objectMapper.nodeFactory.booleanNode(value.value)
        is NormalizedValue.Sequence -> objectMapper.createArrayNode().also { array ->
            value.values.forEach { array.add(encodeValue(it)) }
        }
        is NormalizedValue.ObjectValue -> objectMapper.createObjectNode().also { nested ->
            value.fields.forEach { (field, child) -> nested.set(field.wireName, encodeValue(child)) }
        }
    }

    private fun decodeValue(node: JsonNode): NormalizedValue = when {
        node.isString -> NormalizedValue.identifier(node.stringValue())
        node.isIntegralNumber -> NormalizedValue.number(node.longValue())
        node.isBoolean -> NormalizedValue.flag(node.booleanValue())
        node.isArray -> NormalizedValue.Sequence.of(buildList {
            (node as ArrayNode).forEach { add(decodeValue(it)) }
        })
        node.isObject -> NormalizedValue.ObjectValue.of(
            (node as ObjectNode).properties().associate { (key, value) ->
                NormalizedField.fromWireName(key) to decodeValue(value)
            },
        )
        else -> throw ValidationException("NORMALIZED_VALUE_UNSUPPORTED")
    }
}
