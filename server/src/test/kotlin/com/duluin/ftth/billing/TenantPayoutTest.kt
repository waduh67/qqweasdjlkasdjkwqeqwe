package com.duluin.ftth.billing

import com.duluin.ftth.billing.domain.model.PayoutKind
import com.duluin.ftth.billing.domain.model.PayoutStatus
import com.duluin.ftth.billing.domain.model.TenantPayout
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Menguji siklus-hidup [TenantPayout] tanpa Spring/DB: create → markProcessing → success/failed.
 * Murni domain finansial — nominal harus positif, ref wajib saat processing, rekonsiliasi final.
 */
class TenantPayoutTest {

    @Test
    fun `create menormalkan rekening dan mulai PENDING`() {
        val payout = newPayout(amount = 150_000)

        assertThat(payout.status).isEqualTo(PayoutStatus.PENDING)
        assertThat(payout.kind).isEqualTo(PayoutKind.PAYOUT)
        assertThat(payout.channelCode).isEqualTo("BCA")
        assertThat(payout.pivotRef).isNull()
    }

    @Test
    fun `nominal nol atau negatif ditolak`() {
        assertThatThrownBy { newPayout(amount = 0) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { newPayout(amount = -5) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `markProcessing menyimpan ref dan menaikkan status`() {
        val payout = newPayout(amount = 1000).apply { markProcessing("payout_123") }

        assertThat(payout.status).isEqualTo(PayoutStatus.PROCESSING)
        assertThat(payout.pivotRef).isEqualTo("payout_123")
    }

    @Test
    fun `markProcessing menolak ref kosong`() {
        assertThatThrownBy { newPayout(amount = 1000).markProcessing("  ") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `markSuccess menuntaskan dan menghapus alasan gagal`() {
        val payout = newPayout(amount = 1000).apply {
            markProcessing("ref")
            markFailed("saldo kurang")
            markSuccess()
        }

        assertThat(payout.status).isEqualTo(PayoutStatus.SUCCESS)
        assertThat(payout.failureReason).isNull()
    }

    @Test
    fun `markFailed menyimpan alasan`() {
        val payout = newPayout(amount = 1000).apply { markFailed("rekening ditolak") }

        assertThat(payout.status).isEqualTo(PayoutStatus.FAILED)
        assertThat(payout.failureReason).isEqualTo("rekening ditolak")
    }

    private fun newPayout(amount: Long) = TenantPayout.create(
        tenantId = UuidV7.generate(),
        kind = PayoutKind.PAYOUT,
        amountMinor = amount,
        channelCode = " bca ",
        accountNumber = "1234567890",
        accountName = "  PT Contoh ",
        createdAt = Instant.parse("2026-08-06T00:00:00Z"),
    )
}
