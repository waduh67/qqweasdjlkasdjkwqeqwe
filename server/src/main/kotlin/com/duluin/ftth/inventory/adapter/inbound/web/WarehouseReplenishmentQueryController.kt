package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/replenishments/workbench")
class WarehouseReplenishmentQueryController(private val service: InventoryReplenishmentQueryApi) {
    @GetMapping("/rules")
    fun rules(@RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<WarehousePage<WarehouseReplenishmentRuleView>> {
        val filter = Filter(parameters, "active")
        val active = filter.value("active")?.let { when (it) { "true" -> true; "false" -> false; else -> invalid() } }
        return response(service.rules(filter.page, filter.size, filter.uuid("locationId"), filter.uuid("skuId"), active))
    }

    @GetMapping("/requests")
    fun requests(@RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<WarehousePage<WarehouseReplenishmentRequestView>> {
        val filter = Filter(parameters, "state")
        val state = filter.value("state")?.let { value -> ReplenishmentState.entries.find { it.name == value } ?: invalid() }
        return response(service.requests(filter.page, filter.size, filter.uuid("locationId"), filter.uuid("skuId"), state))
    }

    @GetMapping("/rules/{id}") fun rule(@PathVariable id: UUID) = response(service.rule(id))
    @GetMapping("/requests/{id}") fun request(@PathVariable id: UUID) = response(service.request(id))
    private fun <T> response(value: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)

    private class Filter(private val parameters: MultiValueMap<String, String>, extra: String) {
        init {
            val allowed = setOf("page", "size", "locationId", "skuId", extra)
            if (parameters.any { (key, values) -> key !in allowed || values.size != 1 || values.single().isBlank() || values.single().length > 128 }) invalid()
        }
        fun value(key: String) = parameters[key]?.single()
        private fun number(key: String, default: Int): Int = value(key)?.let {
            if (!it.matches(Regex("[0-9]+"))) invalid()
            it.toIntOrNull() ?: invalid()
        } ?: default
        val page = number("page", 0)
        val size = number("size", 25).also { if (it !in 1..100) invalid() }
        fun uuid(key: String): UUID? = value(key)?.let {
            val id = try { UUID.fromString(it) } catch (_: IllegalArgumentException) { invalid() }
            if (!id.toString().equals(it, ignoreCase = true)) invalid()
            id
        }
    }
    companion object { private fun invalid(): Nothing = masterFailure(WarehouseErrorCode.MALFORMED_REQUEST) }
}
