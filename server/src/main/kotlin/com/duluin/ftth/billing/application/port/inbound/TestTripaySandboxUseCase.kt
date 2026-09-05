package com.duluin.ftth.billing.application.port.inbound

interface TestTripaySandboxUseCase {
    fun testTripay(command: TestTripaySandboxCommand): TripaySandboxTestView
}

data class TestTripaySandboxCommand(
    val merchantCode: String?,
    val apiKey: String?,
    val privateKey: String?,
)

data class TripaySandboxTestView(
    val paymentUrl: String,
)
