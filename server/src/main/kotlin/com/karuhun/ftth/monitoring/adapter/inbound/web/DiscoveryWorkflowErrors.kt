package com.karuhun.ftth.monitoring.adapter.inbound.web

import com.karuhun.ftth.inventory.WarehouseContractException
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

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException::class)
    fun integrity(error: org.springframework.dao.DataIntegrityViolationException) = ResponseEntity.status(409)
        .body(com.karuhun.ftth.inventory.WarehouseError(com.karuhun.ftth.inventory.WarehouseErrorCode.SOURCE_NOT_VERIFIED, "SOURCE_NOT_VERIFIED"))

    @ExceptionHandler(org.springframework.dao.ConcurrencyFailureException::class)
    fun concurrent(error: org.springframework.dao.ConcurrencyFailureException) = ResponseEntity.status(409)
        .body(com.karuhun.ftth.inventory.WarehouseError(com.karuhun.ftth.inventory.WarehouseErrorCode.STALE_REVISION, "STALE_REVISION"))
}
