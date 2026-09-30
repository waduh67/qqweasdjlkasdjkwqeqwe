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
@RestControllerAdvice(assignableTypes = [CustomerAssetOwnershipController::class, CustomerAssetController::class, CustomerController::class])
class CustomerAssetErrors {
    @ExceptionHandler(tools.jackson.core.JacksonException::class, jakarta.validation.ConstraintViolationException::class,
        com.duluin.ftth.common.domain.error.ValidationException::class, org.springframework.http.converter.HttpMessageNotReadableException::class,
        org.springframework.web.bind.MissingRequestValueException::class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class)
    fun malformed(error: Exception) = ResponseEntity.status(400)
        .body(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, "MALFORMED_REQUEST"))
    @ExceptionHandler(WarehouseContractException::class)
    fun contract(error: WarehouseContractException) = ResponseEntity.status(error.error.code.httpStatus).body(error.error)
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict(error: DataIntegrityViolationException) = ResponseEntity.status(409)
        .body(WarehouseError(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "SOURCE_NOT_VERIFIED"))
}
