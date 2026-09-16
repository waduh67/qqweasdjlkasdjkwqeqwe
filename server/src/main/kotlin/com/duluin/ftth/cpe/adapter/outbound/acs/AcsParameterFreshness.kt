package com.duluin.ftth.cpe.adapter.outbound.acs

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.format.DateTimeParseException

internal fun observedParameterTime(root: JsonNode, emptyFallback: Instant? = null): Instant? {
    val pending = ArrayDeque<JsonNode>()
    pending.add(root)
    var oldest: Instant? = null
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        if (node.has("_value")) {
            val raw = node.path("_timestamp")
            if (!raw.isTextual) return null
            val time = try { Instant.parse(raw.asString()) } catch (_: DateTimeParseException) { return null }
            if (oldest == null || time.isBefore(oldest)) oldest = time
        } else {
            node.forEach { pending.add(it) }
        }
    }
    return oldest ?: emptyFallback
}
