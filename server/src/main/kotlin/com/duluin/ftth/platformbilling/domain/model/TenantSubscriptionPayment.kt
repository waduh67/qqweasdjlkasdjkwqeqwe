package com.duluin.ftth.platformbilling.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

/**
 * Catatan satu pembayaran atas tagihan langganan tenant. Append-only. [gatewayRef] mengikuti
 * referensi settlement gateway; [note] mengisi konteks pembayaran manual super-admin.
 * Mirror `billing.domain.model.Payment` tanpa customer.
 */
class TenantSubscriptionPayment private constructor(
    val id: UUID,
    val tenantId: UUID,
    val invoiceId: UUID,
    val amount: BigDecimal,
    val provider: String,
    val gatewayRef: String?,
    val paidAt: Instant,
    val note: String?,
) {
    companion object {
        fun create(
            tenantId: UUID,
            invoiceId: UUID,
            amount: BigDecimal,
            provider: String,
            gatewayRef: String?,
            paidAt: Instant,
            note: String?,
        ): TenantSubscriptionPayment = TenantSubscriptionPayment(
            id = UuidV7.generate(),
            tenantId = tenantId,
            invoiceId = invoiceId,
            amount = validateAmount(amount),
            provider = validateProvider(provider),
            gatewayRef = gatewayRef,
            paidAt = paidAt,
            note = note,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            invoiceId: UUID,
            amount: BigDecimal,
            provider: String,
            gatewayRef: String?,
            paidAt: Instant,
            note: String?,
        ): TenantSubscriptionPayment = TenantSubscriptionPayment(
            id, tenantId, invoiceId, amount, provider, gatewayRef, paidAt, note,
        )

        private fun validateAmount(amount: BigDecimal): BigDecimal {
            if (amount.signum() < 0) throw ValidationException("Nilai pembayaran tidak boleh negatif")
            return amount.setScale(2, RoundingMode.HALF_UP)
        }

        private fun validateProvider(provider: String): String {
            val trimmed = provider.trim()
            if (trimmed.isBlank()) throw ValidationException("Provider pembayaran wajib diisi")
            return trimmed
        }
    }
}
