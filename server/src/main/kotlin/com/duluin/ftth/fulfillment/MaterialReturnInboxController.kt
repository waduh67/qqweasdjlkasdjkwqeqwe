package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.*
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/warehouse/material-returns/pending")
class MaterialReturnInboxController(private val service: MaterialReturnInboxService) {
    @GetMapping fun pending(@RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<WarehousePage<MyMaterialResidual>> {
        fun invalid(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, "Invalid return inbox filter"))
        if (parameters.any { (key, values) -> key !in setOf("page", "size") || values.size != 1 || !values.single().matches(Regex("[0-9]+")) }) invalid()
        fun number(key: String, default: Int) = parameters[key]?.single()?.let { it.toIntOrNull() ?: invalid() } ?: default
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.pending(WarehousePageRequest(number("page", 0), number("size", 25))))
    }
}

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class MaterialReturnInboxService(private val inventory: InventoryMyMaterialsApi, private val cutovers: InventoryTenantCutoverApi) {
    fun pending(page: WarehousePageRequest): WarehousePage<MyMaterialResidual> {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        return inventory.pendingReturns(page)
    }
}
