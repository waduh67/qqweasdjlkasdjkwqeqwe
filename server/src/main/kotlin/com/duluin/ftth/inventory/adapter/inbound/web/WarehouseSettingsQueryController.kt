package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/settings/workbench")
class WarehouseSettingsQueryController(private val service: InventorySettingsQueryApi) {
    @GetMapping("/policy-history")
    fun history(@RequestParam parameters: MultiValueMap<String, String>) = response(service.history(Filter(parameters).page))

    @GetMapping("/delegations")
    fun delegations(@RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<WarehousePage<WarehouseDelegationView>> {
        val filter = Filter(parameters, setOf("locationId", "state"))
        return response(service.delegations(filter.page, filter.uuid("locationId"), filter.value("state")))
    }

    @GetMapping("/delegation-candidates")
    fun candidates(@RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<WarehousePage<WarehousePolicyChoice>> {
        val filter = Filter(parameters, setOf("locationId", "operation", "kind", "approverId", "sourceRoleId", "query"))
        val operation = PolicyOperation.entries.find { it.name == filter.value("operation") } ?: invalid()
        return response(service.candidates(filter.page, filter.uuid("locationId") ?: invalid(), operation,
            filter.value("kind") ?: invalid(), filter.uuid("approverId"), filter.uuid("sourceRoleId"), filter.value("query")))
    }
    private fun <T> response(value: T) = ResponseEntity.ok().header("Cache-Control", "no-store").body(value)
    private class Filter(private val parameters: MultiValueMap<String, String>, extras: Set<String> = emptySet()) {
        init {
            if (parameters.any { (key, values) -> key !in extras + setOf("page", "size") || values.size != 1 || values.single().isBlank() || values.single().length > 200 }) invalid()
        }
        fun value(key: String) = parameters[key]?.single()
        private fun number(key: String, default: Int): Int = value(key)?.let {
            if (!it.matches(Regex("0|[1-9][0-9]*"))) invalid()
            it.toIntOrNull() ?: invalid()
        } ?: default
        val page = WarehousePageRequest(number("page", 0), number("size", 25))
        fun uuid(key: String): UUID? = value(key)?.let {
            val id = try { UUID.fromString(it) } catch (_: IllegalArgumentException) { invalid() }
            if (!id.toString().equals(it, ignoreCase = true)) invalid()
            id
        }
    }
    companion object { private fun invalid(): Nothing = masterFailure(WarehouseErrorCode.MALFORMED_REQUEST) }
}
