package com.duluin.ftth.monitoring.adapter.inbound.web

import com.duluin.ftth.inventory.WarehouseContractException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [DiscoveredOnuController::class])
class DiscoveryWorkflowErrors {
    @ExceptionHandler(WarehouseContractException::class)
    fun contract(error: WarehouseContractException) = ResponseEntity.status(error.error.code.httpStatus).body(error.error)
}
