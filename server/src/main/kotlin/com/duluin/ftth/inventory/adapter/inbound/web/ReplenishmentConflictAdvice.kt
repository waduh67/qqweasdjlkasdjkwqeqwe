package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import org.hibernate.exception.LockAcquisitionException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [WarehouseReplenishmentController::class])
class ReplenishmentConflictAdvice {
    @ExceptionHandler(LockAcquisitionException::class, ConcurrencyFailureException::class)
    fun conflict(error: Exception): ResponseEntity<WarehouseError> = ResponseEntity.status(409)
        .body(WarehouseError(WarehouseErrorCode.STALE_REVISION, "Concurrent inventory activity; reload and retry"))
}
