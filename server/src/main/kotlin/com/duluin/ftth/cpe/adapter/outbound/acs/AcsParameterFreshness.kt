package com.duluin.ftth.cpe.adapter.outbound.acs

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.format.DateTimeParseException

internal data class AcsParameterTime(val earliest: Instant?, val invalid: Boolean, val knownEarliest: Instant?, val missing: Boolean)

internal fun observedParameterTime(root: JsonNode, emptyFallback: Instant? = null): Instant? =
    parameterTimeEvidence(root, emptyFallback).earliest

internal fun parameterTimeEvidence(root: JsonNode, emptyFallback: Instant? = null): AcsParameterTime {
    val pending = ArrayDeque<JsonNode>()
    pending.add(root)
    var oldest: Instant? = null
    var missing = false
    var invalid = false
    val latestAllowed = Instant.now().plusSeconds(300)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        if (node.has("_value")) {
            val raw = node.path("_timestamp")
            if (raw.isMissingNode || raw.isNull) { missing = true; continue }
            if (!raw.isTextual) { invalid = true; continue }
            val time = try { Instant.parse(raw.asString()) } catch (_: DateTimeParseException) { invalid = true; continue }
            if (time.isAfter(latestAllowed)) { invalid = true; continue }
            if (oldest == null || time.isBefore(oldest)) oldest = time
        } else {
            node.forEach { pending.add(it) }
        }
    }
    return AcsParameterTime(if (missing || invalid) null else oldest ?: emptyFallback, invalid, oldest, missing)
}
