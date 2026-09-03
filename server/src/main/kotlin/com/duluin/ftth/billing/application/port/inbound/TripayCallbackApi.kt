package com.duluin.ftth.billing.application.port.inbound

import org.springframework.modulith.NamedInterface

/**
 * Authenticates and processes the single Tripay callback URL shared by tenant BYOK accounts.
 * Raw request bytes are deliberately part of the contract because Tripay signs those bytes.
 */
@NamedInterface("gateway")
interface TripayCallbackApi {
    fun handlePayment(rawBody: ByteArray, callbackSignature: String)
}
