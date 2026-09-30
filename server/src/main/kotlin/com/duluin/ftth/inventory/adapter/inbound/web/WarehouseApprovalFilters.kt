package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.common.domain.identity.SerialIdentity
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import java.time.Instant
import java.util.UUID

internal object WarehouseApprovalFilters {
    fun parse(parameters: Map<String, List<String>>, history: Boolean = false): WarehouseApprovalFilter {
        val allowed = if (history) setOf("page", "size") else setOf("page", "size", "status", "sourceDocumentId", "query", "operation", "locationId", "skuId", "serial", "from", "until")
        if (parameters.any { (key, values) -> key !in allowed || values.size != 1 || values.single().isBlank() || values.single().length > 200 }) invalid()
        fun value(key: String) = parameters[key]?.single()
        try {
            fun number(key: String, default: Int) = value(key)?.let { if (!it.matches(Regex("[0-9]+"))) invalid(); it.toInt() } ?: default
            fun uuid(key: String) = value(key)?.let { UUID.fromString(it).also { id -> if (!id.toString().equals(it, true)) invalid() } }
            return WarehouseApprovalFilter(number("page", 0), number("size", 25), value("status")?.let(WarehouseApprovalStatus::valueOf),
                uuid("sourceDocumentId"), value("query"), value("operation")?.let(PolicyOperation::valueOf), uuid("locationId"), uuid("skuId"),
                value("serial")?.let { SerialIdentity.parse(it).canonical }, value("from")?.let(Instant::parse), value("until")?.let(Instant::parse))
        } catch (_: IllegalArgumentException) { invalid() }
          catch (_: java.time.DateTimeException) { invalid() }
    }
    private fun invalid(): Nothing = masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
}
