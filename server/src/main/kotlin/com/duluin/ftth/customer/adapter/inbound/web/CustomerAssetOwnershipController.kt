package com.duluin.ftth.customer.adapter.inbound.web

import com.duluin.ftth.customer.CustomerAssetOwnershipApi
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
class CustomerAssetOwnershipController(private val ownership: CustomerAssetOwnershipApi) {
    @GetMapping("/api/customers/{id}/assets/ownership")
    fun current(@PathVariable id: UUID) = ownership.current(id)

    @GetMapping("/api/customers/{id}/assets/{assignmentId}/exceptions/context")
    fun exceptionContext(@PathVariable id: UUID, @PathVariable assignmentId: UUID) = ownership.exceptionContext(id, assignmentId)
}
