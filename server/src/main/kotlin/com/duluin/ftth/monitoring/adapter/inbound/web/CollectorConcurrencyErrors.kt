package com.duluin.ftth.monitoring.adapter.inbound.web

import org.springframework.dao.ConcurrencyFailureException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [CollectorGatewayController::class])
class CollectorConcurrencyErrors {
    @ExceptionHandler(ConcurrencyFailureException::class)
    fun retry(): ResponseEntity<Map<String, String>> = ResponseEntity.status(409).header("Retry-After", "1")
        .body(mapOf("code" to "OBSERVATION_RETRY"))
}
