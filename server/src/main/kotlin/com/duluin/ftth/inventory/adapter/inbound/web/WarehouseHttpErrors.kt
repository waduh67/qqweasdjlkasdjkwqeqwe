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
@RestControllerAdvice(assignableTypes = [WarehouseReportController::class, WarehouseAssetLossController::class, WarehouseCompensationController::class, WarehouseDispositionController::class, WarehouseRmaHandoverController::class, WarehouseReturnController::class, WarehouseReplenishmentController::class, WarehouseCountController::class, WarehouseTransferController::class, AssetTitleCorrectionController::class, InventoryController::class, MaterialTemplateController::class, WarehousePolicyController::class, InventoryApprovalController::class, WarehouseMasterController::class, WarehouseReceiptController::class, WarehouseOpeningBalanceController::class, WarehouseQueryController::class, InventoryQueryController::class, WarehouseReservationController::class])
class WarehouseHttpErrors {
    @ExceptionHandler(WarehouseContractException::class)
    fun contract(error: WarehouseContractException) = ResponseEntity.status(error.error.code.httpStatus).body(error.error)

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException::class)
    fun conflict(error: org.springframework.dao.DataIntegrityViolationException) = response(WarehouseErrorCode.SOURCE_NOT_VERIFIED)

    @ExceptionHandler(JacksonException::class, ValidationException::class, HttpMessageNotReadableException::class,
        MissingRequestValueException::class, MethodArgumentTypeMismatchException::class, org.springframework.web.multipart.MultipartException::class)
    fun malformed(error: Exception) = response(WarehouseErrorCode.MALFORMED_REQUEST)

    @ExceptionHandler(AccessDeniedException::class, org.springframework.security.access.AccessDeniedException::class)
    fun forbidden(error: Exception) = response(WarehouseErrorCode.FORBIDDEN)

    @ExceptionHandler(AuthenticationException::class)
    fun anonymous(error: Exception) = response(WarehouseErrorCode.UNAUTHENTICATED)

    private fun response(code: WarehouseErrorCode) = ResponseEntity.status(code.httpStatus).body(WarehouseError(code, code.name))
}
