package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import tools.jackson.databind.JsonNode

/** Export only the already authorized, public report DTO; never a database row or operation payload. */
internal object WarehouseReportCsv {
    const val MAX_ROWS = 1000

    fun render(page: JsonNode): String {
        if (page.path("totalElements").asLong() > MAX_ROWS) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val rows = page.path("items").map { flatten(it) }
        val columns = rows.flatMap { it.keys }.distinct().sorted()
        return (listOf(columns) + rows.map { row -> columns.map { row[it].orEmpty() } })
            .joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",", transform = ::cell) }
    }

    private fun flatten(node: JsonNode, prefix: String = ""): Map<String, String> = buildMap {
        if (node.isObject) node.properties().forEach { (name, value) ->
            putAll(flatten(value, if (prefix.isEmpty()) name else "$prefix.$name"))
        } else put(prefix, if (node.isNull) "" else if (node.isValueNode) node.asString() else node.toString())
    }

    internal fun cell(value: String): String {
        // Spreadsheet importers may discard whitespace/control characters before evaluating a cell.
        val first = value.dropWhile { it.isWhitespace() || it.isISOControl() || it == '\uFEFF' }.firstOrNull()
        val safe = if (first in listOf('=', '+', '-', '@') || value.any { it == '\t' || it == '\r' || it == '\n' }) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
