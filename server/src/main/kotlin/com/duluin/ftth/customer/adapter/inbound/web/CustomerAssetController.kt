package com.duluin.ftth.customer.adapter.inbound.web

import com.duluin.ftth.customer.CustomerAssetApi
import com.duluin.ftth.customer.InstallCustomerAssetRequest
import com.duluin.ftth.inventory.WarehouseMutationMetadata
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
class CustomerAssetController(private val assets: CustomerAssetApi) {
    @PostMapping("/api/customers/{id}/assets/install")
    @ResponseStatus(HttpStatus.CREATED)
    fun install(@PathVariable id: java.util.UUID, @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: InstallCustomerAssetRequest) = assets.install(id, request, WarehouseMutationMetadata(key))
}
