package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.common.domain.identity.SerialIdentity
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import java.time.Instant
import java.util.UUID

internal object WarehouseCountFilters {
    fun parse(parameters: Map<String, List<String>>, mode: String = "list"): WarehouseCountFilter {
        val allowed = when (mode) {
            "history" -> setOf("page", "size")
            "counters" -> setOf("page", "size", "query")
            "positions" -> setOf("page", "size", "locationId", "skuId", "serial", "query")
            else -> setOf("page", "size", "state", "locationId", "skuId", "serial", "query", "from", "until")
        }
        if (parameters.any { (key, values) -> key !in allowed || values.size != 1 || values.single().isBlank() || values.single().length > 200 }) invalid()
        fun value(key: String) = parameters[key]?.single()
        try {
            fun number(key: String, default: Int) = value(key)?.let { if (!it.matches(Regex("[0-9]+"))) invalid(); it.toInt() } ?: default
            fun uuid(key: String) = value(key)?.let { UUID.fromString(it).also { id -> if (!id.toString().equals(it, true)) invalid() } }
            return WarehouseCountFilter(number("page", 0), number("size", 25), value("state")?.let(WarehouseCountState::valueOf),
                uuid("locationId"), uuid("skuId"), value("serial")?.let { SerialIdentity.parse(it).canonical }, value("query"),
                value("from")?.let(Instant::parse), value("until")?.let(Instant::parse))
        } catch (_: IllegalArgumentException) { invalid() }
          catch (_: java.time.DateTimeException) { invalid() }
    }
    private fun invalid(): Nothing = masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
}
