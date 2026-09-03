package com.duluin.ftth.platformbilling.adapter.inbound.web

import com.duluin.ftth.billing.application.port.inbound.TripayCallbackApi
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/platform/tripay/callbacks")
@Tag(name = "Platform — Callback Tripay")
class TripayCallbackController(
    private val callbacks: TripayCallbackApi,
) {

    @PostMapping(
        "/payment",
        consumes = [MediaType.ALL_VALUE],
        headers = [CALLBACK_SIGNATURE_HEADER],
    )
    @Operation(summary = "Callback pembayaran Tripay tenant BYOK")
    fun payment(
        @RequestBody rawBody: ByteArray,
        @RequestHeader(name = CALLBACK_SIGNATURE_HEADER) callbackSignature: String,
    ): Map<String, Boolean> {
        callbacks.handlePayment(rawBody, callbackSignature)
        return mapOf("success" to true)
    }

    private companion object {
        const val CALLBACK_SIGNATURE_HEADER = "X-Callback-Signature"
    }
}
