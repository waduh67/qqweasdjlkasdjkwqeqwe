package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.PaymentMethodCatalog
import com.duluin.ftth.billing.adapter.outbound.gateway.TripayPaymentGateway
import com.duluin.ftth.billing.application.port.inbound.TestTripaySandboxCommand
import com.duluin.ftth.billing.application.port.inbound.TestTripaySandboxUseCase
import com.duluin.ftth.billing.application.port.inbound.TripaySandboxTestView
import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.net.URI
import java.util.Locale

@Service
@Transactional(readOnly = true)
class TripaySandboxTestService(
    private val repository: TenantPaymentGatewayRepository,
    private val tripayGateway: TripayPaymentGateway,
) : TestTripaySandboxUseCase {

    override fun testTripay(command: TestTripaySandboxCommand): TripaySandboxTestView {
        val merchantCode = command.merchantCode.normalizedSecret()
            ?: throw ValidationException("Merchant code Tripay wajib diisi")
        val stored = repository.find()?.tripay
        val apiKey = command.apiKey.normalizedSecret()
            ?: stored?.apiKeyForGateway().normalizedSecret()
            ?: throw ValidationException("API key Tripay wajib diisi atau disimpan terlebih dahulu")
        val privateKey = command.privateKey.normalizedSecret()
            ?: stored?.privateKeyForGateway().normalizedSecret()
            ?: throw ValidationException("Private key Tripay wajib diisi atau disimpan terlebih dahulu")
        val merchantRef = "TST-${UuidV7.generate()}"

        val result = tripayGateway.createCharge(
            request = ChargeRequest(
                invoiceNumber = merchantRef,
                amount = TEST_AMOUNT,
                customerName = "Tripay Sandbox Test",
                customerEmail = "tripay-test@example.com",
                description = "Tripay sandbox connection test",
                method = PaymentMethodCatalog.METHOD_QRIS,
            ),
            ctx = ResolvedGatewayContext(
                provider = tripayGateway.provider,
                mode = GatewayMode.BYO,
                secretKey = privateKey,
                webhookToken = null,
                apiKey = apiKey,
                merchantCode = merchantCode,
                sandbox = true,
            ),
        )

        return TripaySandboxTestView(
            paymentUrl = safePaymentUrl(result.payUrl, apiKey, privateKey),
        )
    }

    private fun safePaymentUrl(raw: String?, apiKey: String, privateKey: String): String {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ConflictException("Tripay sandbox tidak mengembalikan payment URL")
        val uri = runCatching { URI(value) }.getOrNull()
            ?: throw ConflictException("Tripay sandbox mengembalikan payment URL yang tidak valid")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in SAFE_URL_SCHEMES || uri.host.isNullOrBlank()) {
            throw ConflictException("Tripay sandbox mengembalikan payment URL yang tidak valid")
        }
        val query = uri.rawQuery?.lowercase(Locale.ROOT).orEmpty()
        if (value.contains(apiKey) || value.contains(privateKey) || "signature=" in query) {
            throw ConflictException("Tripay sandbox mengembalikan payment URL yang tidak aman")
        }
        return value
    }

    private fun String?.normalizedSecret(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        val TEST_AMOUNT: BigDecimal = BigDecimal("1000")
        val SAFE_URL_SCHEMES: Set<String> = setOf("http", "https")
    }
}
