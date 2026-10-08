package com.duluin.ftth.workorder.adapter.inbound.web

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestValueException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import tools.jackson.core.JacksonException

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [ReferenceWorkOrderController::class])
class ReferenceWorkOrderHttpErrors {
    @ExceptionHandler(WarehouseContractException::class)
    fun contract(error: WarehouseContractException) = ResponseEntity.status(error.error.code.httpStatus).body(error.error)

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict(error: DataIntegrityViolationException) = response(WarehouseErrorCode.SOURCE_NOT_VERIFIED)

    @ExceptionHandler(JacksonException::class, ValidationException::class, HttpMessageNotReadableException::class,
        MissingRequestValueException::class, MethodArgumentTypeMismatchException::class, org.springframework.web.multipart.MultipartException::class)
    fun malformed(error: Exception) = response(WarehouseErrorCode.MALFORMED_REQUEST)

    @ExceptionHandler(AccessDeniedException::class, org.springframework.security.access.AccessDeniedException::class)
    fun forbidden(error: Exception) = response(WarehouseErrorCode.FORBIDDEN)

    private fun response(code: WarehouseErrorCode) = ResponseEntity.status(code.httpStatus).body(WarehouseError(code, code.name))
}
