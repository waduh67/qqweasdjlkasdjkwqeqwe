package com.karuhun.ftth.fulfillment

import com.karuhun.ftth.common.infrastructure.web.StrictCommandJson
import com.karuhun.ftth.customer.ReplaceCustomerAssetRequest
import com.karuhun.ftth.customer.CustomerAssetRelocationApi
import com.karuhun.ftth.customer.CustomerAssetRelocationContext
import com.karuhun.ftth.customer.CustomerAssetRelocationRequest
import com.karuhun.ftth.inventory.InventoryAssetReplacementApi
import com.karuhun.ftth.inventory.RemovePhysicalAssetRequest
import com.karuhun.ftth.inventory.WarehouseMutationMetadata
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
class AssetReplacementController(private val service: AssetReplacementService, private val relocation: CustomerAssetRelocationApi,
    private val inventory: InventoryAssetReplacementApi, private val delivery: AssetProvisioningDelivery) {
    @PostMapping("/api/customers/{id}/assets/replace")
    @ResponseStatus(HttpStatus.CREATED)
    fun replace(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        service.replace(id, StrictCommandJson.decode(body, ReplaceCustomerAssetRequest::class.java), WarehouseMutationMetadata(key))

    @PostMapping("/api/customers/{id}/assets/remove")
    fun remove(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        service.remove(id, StrictCommandJson.decode(body, RemovePhysicalAssetRequest::class.java), WarehouseMutationMetadata(key))

    @PostMapping("/api/customers/{id}/assets/{assignmentId}/relocate")
    fun relocate(@PathVariable id: UUID, @PathVariable assignmentId: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        relocation.relocate(CustomerAssetRelocationContext(id, assignmentId), StrictCommandJson.decode(body, CustomerAssetRelocationRequest::class.java), WarehouseMutationMetadata(key))

    @PostMapping("/api/customers/{id}/asset-operations/{operationId}/retry")
    fun retry(@PathVariable id: UUID, @PathVariable operationId: UUID): Map<String, Boolean> {
        inventory.authorizeOutcome(id, operationId)
        return mapOf("delivered" to delivery.deliver(operationId))
    }
}
