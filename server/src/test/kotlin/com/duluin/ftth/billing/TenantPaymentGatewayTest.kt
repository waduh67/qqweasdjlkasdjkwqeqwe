package com.duluin.ftth.billing

import com.duluin.ftth.billing.domain.model.ManualPaymentConfig
import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.common.domain.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Menguji keputusan inti [TenantPaymentGateway] tanpa Spring/DB setelah reshape ke model Pivot
 * "business as platform": tak ada lagi kredensial gateway di sini — hanya metode aktif (PIVOT/MANUAL)
 * + konfigurasi pembayaran manual. Resolusi kredensial Pivot pindah ke `TenantPaymentGatewayResolver`
 * (master config + sub-account). Murni domain — cepat & deterministik.
 */
class TenantPaymentGatewayTest {

    // --- metode aktif & penanda usesPivot ---

    @Test
    fun `bawaan tenant MANUAL nonaktif`() {
        val gw = defaultGateway()
        assertThat(gw.provider).isEqualTo(PaymentProvider.MANUAL)
        assertThat(gw.enabled).isFalse()
        assertThat(gw.usesPivot).isFalse()
    }

    @Test
    fun `PIVOT aktif menandai usesPivot`() {
        val gw = defaultGateway().apply { update(PaymentProvider.PIVOT, enabled = true) }
        assertThat(gw.usesPivot).isTrue()
    }

    @Test
    fun `PIVOT dipilih tapi nonaktif tidak usesPivot`() {
        val gw = defaultGateway().apply { update(PaymentProvider.PIVOT, enabled = false) }
        assertThat(gw.usesPivot).isFalse()
    }

    @Test
    fun `MANUAL aktif tidak usesPivot`() {
        val gw = defaultGateway().apply { update(PaymentProvider.MANUAL, enabled = true) }
        assertThat(gw.usesPivot).isFalse()
    }

    // --- pembayaran manual (tunai/transfer/QRIS) ---

    @Test
    fun `update menyimpan konfigurasi manual ternormalisasi (whitespace jadi null)`() {
        val gw = defaultGateway().apply {
            update(
                PaymentProvider.MANUAL,
                enabled = false,
                manual = ManualPaymentConfig(
                    transferEnabled = true,
                    bankName = "  BCA  ",
                    accountNumber = "1234567890",
                    accountHolder = "   ", // whitespace → null
                    qrisEnabled = true,
                ),
            )
        }

        assertThat(gw.manual.transferEnabled).isTrue()
        assertThat(gw.manual.bankName).isEqualTo("BCA")
        assertThat(gw.manual.accountNumber).isEqualTo("1234567890")
        assertThat(gw.manual.accountHolder).isNull()
        assertThat(gw.manual.qrisEnabled).isTrue()
    }

    @Test
    fun `update tanpa manual mengosongkan konfigurasi manual (semantik selalu diganti)`() {
        val gw = defaultGateway().apply {
            update(
                PaymentProvider.MANUAL, enabled = false,
                manual = ManualPaymentConfig(transferEnabled = true, bankName = "BNI"),
            )
        }

        // Update berikutnya tanpa argumen manual → kembali ke EMPTY (bukan pertahankan).
        gw.update(PaymentProvider.MANUAL, enabled = false)

        assertThat(gw.manual).isEqualTo(ManualPaymentConfig.EMPTY)
    }

    @Test
    fun `attachQrisImage mengeset penanda lalu clearQrisImage melepasnya`() {
        val gw = defaultGateway()
        assertThat(gw.qrisImageSet).isFalse()

        gw.attachQrisImage(storageKey = "t1/billing/gateway/qris", contentType = "image/png")
        assertThat(gw.qrisImageSet).isTrue()
        assertThat(gw.qrisStorageKey).isEqualTo("t1/billing/gateway/qris")
        assertThat(gw.qrisContentType).isEqualTo("image/png")

        gw.clearQrisImage()
        assertThat(gw.qrisImageSet).isFalse()
        assertThat(gw.qrisStorageKey).isNull()
        assertThat(gw.qrisContentType).isNull()
    }

    // --- perkakas uji ---

    private fun defaultGateway() = TenantPaymentGateway.defaultFor(UuidV7.generate())
}
