package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest

class WarehouseCanonicalPayload private constructor(val json: String, val hash: String) {
    companion object {
        private val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
        fun parse(raw: String): WarehouseCanonicalPayload {
            try {
                require(raw.length in 1..1_048_576)
                val tree = mapper.readTree(raw)
                require(tree.isObject)
                val canonical = encode(tree)
                val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                return WarehouseCanonicalPayload(canonical, hash)
            } catch (failure: Exception) {
                throw WarehouseContractException(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, "Invalid canonical command payload"))
            }
        }
        private fun encode(node: JsonNode): String = when {
            node.isObject -> node.properties().sortedBy { it.key }.joinToString(",", "{", "}") {
                mapper.writeValueAsString(it.key) + ":" + encode(it.value)
            }
            node.isArray -> node.joinToString(",", "[", "]", transform = ::encode)
            node.isFloatingPointNumber -> error("Use exact string quantities")
            else -> mapper.writeValueAsString(node)
        }
    }
}
