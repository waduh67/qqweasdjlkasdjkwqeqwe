package com.duluin.ftth.billing.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

/**
 * Catatan satu pembayaran atas sebuah tagihan.
 *
 * Bersifat append-only: pembayaran yang tercatat tidak pernah diubah. Ditaut ke
 * `invoice` (FK intra-module diperbolehkan) dan ke pelanggan lewat UUID polos.
 * [gatewayRef] mengikuti referensi settlement gateway; [note] mengisi konteks
 * pembayaran manual (mis. nomor bukti transfer).
 */
class Payment private constructor(
    val id: UUID,
    val tenantId: UUID,
    val invoiceId: UUID,
    val customerId: UUID,
    val amount: BigDecimal,
    val provider: String,
    val gatewayRef: String?,
    val paidAt: Instant,
    val note: String?,
) {
    companion object {
        @Suppress("LongParameterList")
        fun create(
            tenantId: UUID,
            invoiceId: UUID,
            customerId: UUID,
            amount: BigDecimal,
            provider: String,
            gatewayRef: String?,
            paidAt: Instant,
            note: String?,
        ): Payment = Payment(
            id = UuidV7.generate(),
            tenantId = tenantId,
            invoiceId = invoiceId,
            customerId = customerId,
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
            customerId: UUID,
            amount: BigDecimal,
            provider: String,
            gatewayRef: String?,
            paidAt: Instant,
            note: String?,
        ): Payment = Payment(id, tenantId, invoiceId, customerId, amount, provider, gatewayRef, paidAt, note)

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
