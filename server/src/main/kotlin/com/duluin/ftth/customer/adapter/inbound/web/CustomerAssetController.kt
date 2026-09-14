package com.duluin.ftth.customer.adapter.inbound.web

import com.duluin.ftth.customer.CustomerAssetApi
import com.duluin.ftth.customer.InstallCustomerAssetRequest
import com.duluin.ftth.inventory.WarehouseMutationMetadata
import com.duluin.ftth.common.infrastructure.web.StrictCommandJson
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
class CustomerAssetController(private val assets: CustomerAssetApi) {
    @PostMapping("/api/customers/{id}/assets/install")
    @ResponseStatus(HttpStatus.CREATED)
    fun install(@PathVariable id: java.util.UUID, @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: String) = assets.install(id, StrictCommandJson.decode(body, InstallCustomerAssetRequest::class.java), WarehouseMutationMetadata(key))
}
