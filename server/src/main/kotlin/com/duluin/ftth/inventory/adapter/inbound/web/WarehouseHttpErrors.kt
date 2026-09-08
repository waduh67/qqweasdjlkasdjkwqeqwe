package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.*
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestValueException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import tools.jackson.core.JacksonException

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [WarehouseMasterController::class, WarehouseReceiptController::class])
class WarehouseHttpErrors {
    @ExceptionHandler(WarehouseContractException::class)
    fun contract(error: WarehouseContractException) = ResponseEntity.status(error.error.code.httpStatus).body(error.error)

    @ExceptionHandler(JacksonException::class, ValidationException::class, HttpMessageNotReadableException::class,
        MissingRequestValueException::class, MethodArgumentTypeMismatchException::class)
    fun malformed(error: Exception) = response(WarehouseErrorCode.MALFORMED_REQUEST)

    @ExceptionHandler(AccessDeniedException::class)
    fun forbidden(error: Exception) = response(WarehouseErrorCode.FORBIDDEN)

    @ExceptionHandler(AuthenticationException::class)
    fun anonymous(error: Exception) = response(WarehouseErrorCode.UNAUTHENTICATED)

    private fun response(code: WarehouseErrorCode) = ResponseEntity.status(code.httpStatus).body(WarehouseError(code, code.name))
}
