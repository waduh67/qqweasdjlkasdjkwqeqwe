package com.duluin.ftth.customer.adapter.inbound.web

import com.duluin.ftth.customer.CustomerAssetOwnershipApi
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
class CustomerAssetOwnershipController(private val ownership: CustomerAssetOwnershipApi) {
    @GetMapping("/api/customers/{id}/assets/ownership")
    fun current(@PathVariable id: UUID) = ownership.current(id)
}
