package com.duluin.ftth.portal.adapter.inbound.web

import com.duluin.ftth.inventory.InventoryAssetPresentationApi
import com.duluin.ftth.inventory.WarehousePageRequest
import com.duluin.ftth.portal.PortalCustomerSession
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/portal/me/assets")
class PortalAssetController(private val service: PortalAssetService) {
    @GetMapping fun list(@RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<*> {
        if (parameters.any { (key, values) -> key !in setOf("page", "size") || values.size != 1 || !values.single().matches(Regex("[0-9]+")) }) invalid()
        fun number(key: String, default: Int) = parameters[key]?.single()?.let { it.toIntOrNull() ?: invalid() } ?: default
        val page = WarehousePageRequest(number("page", 0), number("size", 10))
        if (page.page < 0 || page.size !in 1..100) invalid()
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.list(page))
    }
    private fun invalid(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Filter perangkat tidak valid")
}

@Service
@Transactional(readOnly = true, timeout = 20)
class PortalAssetService(private val session: PortalCustomerSession, private val assets: InventoryAssetPresentationApi) {
    fun list(page: WarehousePageRequest) = assets.forCustomer(session.currentCustomerId(), page)
}
