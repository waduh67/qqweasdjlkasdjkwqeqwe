package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.inbound.OpeningBalanceInput
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.service.WarehouseOpeningBalanceService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.MultipartHttpServletRequest

@RestController
@RequestMapping("/api/v1/warehouse/opening-balances")
class WarehouseOpeningBalanceController(private val openings: WarehouseOpeningBalanceService) {
    @PostMapping("/requests", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun request(@RequestHeader("Idempotency-Key") key: String, @RequestParam("request") body: String,
        @RequestParam file: MultipartFile, request: MultipartHttpServletRequest): Nothing {
        if (request.parameterMap.keys != setOf("request") || request.parameterMap.values.any { it.size != 1 } ||
            request.multiFileMap.keys != setOf("file") || request.multiFileMap["file"]?.size != 1 || file.size > 15728640)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        openings.request(WarehouseReceiptJson.decode(body, OpeningBalanceInput::class.java), key, file.contentType ?: "", file.bytes)
    }
}
