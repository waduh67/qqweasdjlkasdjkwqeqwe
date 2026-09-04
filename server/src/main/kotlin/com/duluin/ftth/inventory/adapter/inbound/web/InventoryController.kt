package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.InventoryApi
import com.duluin.ftth.inventory.InventoryAssetRef
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/inventory")
class InventoryController(private val inventory: InventoryApi) {
    @GetMapping("/serialized/{id}")
    @PreAuthorize("@authz.can('inventory.item.view')")
    fun get(@PathVariable id: UUID): InventoryAssetRef = inventory.findSerializedAsset(id) ?: error("serialized asset not found")

    @PostMapping("/serialized/{id}/installed-onu")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("@authz.can('inventory.custody.manage')")
    fun link(@PathVariable id: UUID, @Valid @RequestBody request: LinkOnuRequest): InventoryAssetRef =
        inventory.linkInstalledOnu(id, request.onuId, request.operationKey)
}

data class LinkOnuRequest(val onuId: UUID, @field:NotBlank val operationKey: String)
