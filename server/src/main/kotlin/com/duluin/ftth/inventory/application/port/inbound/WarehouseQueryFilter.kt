package com.duluin.ftth.inventory.application.port.inbound

import com.duluin.ftth.common.domain.identity.SerialIdentity
import com.duluin.ftth.inventory.*
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class WarehouseQueryFilter(
    val page: Int = 0, val size: Int = 25, val sort: String = "name", val direction: String = "asc",
    val skuId: UUID? = null, val serial: String? = null, val locationId: UUID? = null,
    val status: String? = null, val condition: String? = null, val owner: String? = null,
    val from: Instant? = null, val until: Instant? = null,
) {
    companion object {
        fun parse(parameters: Map<String, List<String>>, history: Boolean = false): WarehouseQueryFilter {
            val allowed = setOf("page", "size", "sort", "direction", "skuId", "serial", "locationId", "status", "condition", "owner", "from", "until")
            if (parameters.any { (key, values) -> key !in allowed || values.size != 1 || values.single().isBlank() || values.single().length > 128 }) invalid()
            fun value(key: String) = parameters[key]?.single()
            try {
                val page = value("page")?.let { if (!it.matches(Regex("[0-9]+"))) invalid(); it.toInt() } ?: 0
                val size = value("size")?.let { if (!it.matches(Regex("[0-9]+"))) invalid(); it.toInt() } ?: 25
                val sort = value("sort") ?: if (history) "createdAt" else "name"
                val direction = value("direction") ?: "asc"
                if (page < 0 || size !in 1..100 || sort !in setOf("name", "createdAt", "id") || direction !in setOf("asc", "desc")) invalid()
                if (history && sort != "createdAt" && sort != "id") invalid()
                val status = value("status")
                if (status != null && status !in setOf("AVAILABLE", "RESERVED", "PICKED", "ISSUED", "IN_TRANSIT", "CONSUMED", "INSTALLED",
                    "PROVISIONAL", "RETURNED", "QUARANTINE", "LOST", "DISPOSED", "RECEIPT_SOURCE", "ACTIVE", "SPLIT", "RETIRED")) invalid()
                val condition = value("condition")?.also { WarehouseCondition.valueOf(it) }
                val owner = value("owner")?.also { AssetLegalOwner.valueOf(it) }
                val from = value("from")?.let(Instant::parse)
                val until = value("until")?.let(Instant::parse)
                if ((from == null) != (until == null) || (from != null && until != null &&
                    (from >= until || Duration.between(from, until) > Duration.ofDays(366)))) invalid()
                fun uuid(key: String): UUID? = value(key)?.let {
                    val parsed = UUID.fromString(it)
                    if (!parsed.toString().equals(it, ignoreCase = true)) invalid()
                    parsed
                }
                return WarehouseQueryFilter(page, size, sort, direction, uuid("skuId"), value("serial")?.let { SerialIdentity.parse(it).canonical },
                    uuid("locationId"), status, condition, owner, from, until)
            } catch (_: IllegalArgumentException) { invalid() }
              catch (_: java.time.DateTimeException) { invalid() }
        }
        private fun invalid(): Nothing = masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }
}
