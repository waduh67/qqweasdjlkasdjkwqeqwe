package com.duluin.ftth.customer.adapter.inbound.web

import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [CustomerAssetController::class, CustomerController::class])
class CustomerAssetErrors {
    @ExceptionHandler(WarehouseContractException::class)
    fun contract(error: WarehouseContractException) = ResponseEntity.status(error.error.code.httpStatus).body(error.error)
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict(error: DataIntegrityViolationException) = ResponseEntity.status(409)
        .body(WarehouseError(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "SOURCE_NOT_VERIFIED"))
}
