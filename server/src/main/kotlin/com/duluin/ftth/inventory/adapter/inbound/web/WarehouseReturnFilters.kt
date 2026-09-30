package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.common.domain.identity.SerialIdentity
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal object WarehouseReturnFilters {
    fun parse(parameters: Map<String, List<String>>, sources: Boolean = false, history: Boolean = false): WarehouseReturnFilter {
        val allowed = if (history) setOf("page", "size") else setOf("page", "size", "origin", "locationId", "skuId",
            "stockIdentityId", "owner", "serial", "query", "from", "until") + if (sources) emptySet() else setOf("state")
        if (parameters.any { (key, values) -> key !in allowed || values.size != 1 || values.single().isBlank() || values.single().length > 200 }) invalid()
        fun value(key: String) = parameters[key]?.single()
        try {
            fun number(key: String, default: Int) = value(key)?.let { if (!it.matches(Regex("[0-9]+"))) invalid(); it.toInt() } ?: default
            fun uuid(key: String) = value(key)?.let { UUID.fromString(it).also { id -> if (!id.toString().equals(it, true)) invalid() } }
            val filter = WarehouseReturnFilter(number("page", 0), number("size", 25), value("origin")?.let(WarehouseReturnOrigin::valueOf),
                value("state")?.let(WarehouseReturnState::valueOf), uuid("locationId"), uuid("skuId"), uuid("stockIdentityId"),
                value("owner")?.let(AssetLegalOwner::valueOf), value("serial")?.let { SerialIdentity.parse(it).canonical },
                value("query"), value("from")?.let(Instant::parse), value("until")?.let(Instant::parse))
            validate(filter)
            return filter
        } catch (_: IllegalArgumentException) { invalid() }
          catch (_: java.time.DateTimeException) { invalid() }
    }

    private fun validate(filter: WarehouseReturnFilter) {
        if (filter.page < 0 || filter.size !in 1..100 || (filter.from == null) != (filter.until == null) ||
            (filter.from != null && filter.until != null && (filter.from >= filter.until || Duration.between(filter.from, filter.until) > Duration.ofDays(366)))) invalid()
    }
    private fun invalid(): Nothing = masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
}
