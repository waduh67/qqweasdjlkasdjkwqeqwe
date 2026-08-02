package com.duluin.ftth.billing

import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.billing.domain.model.PlatformGatewayCreds
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Menguji keputusan inti [TenantPaymentGateway] tanpa Spring/DB: resolusi kredensial
 * ([resolve]) lintas provider/mode, perilaku "secret kosong = pertahankan" pada [update],
 * dan penguncian mode PLATFORM lewat [provisionPlatform]. Murni domain — cepat & deterministik.
 */
class TenantPaymentGatewayTest {

    private val platform = PlatformGatewayCreds(secretKey = "xnd_master", webhookToken = "plat_tok", feeRuleId = "fee_1")

    // --- resolve: gateway mati / bawaan ---

    @Test
    fun `bawaan tenant MANUAL nonaktif meresolusi null`() {
        assertThat(defaultGateway().resolve(platform)).isNull()
    }

    @Test
    fun `gateway mati meresolusi null berapa pun providernya`() {
        val gw = defaultGateway().apply {
            update(PaymentProvider.XENDIT, GatewayMode.BYO, enabled = false, apiKey = null, secretKey = "xnd_key", webhookToken = "tok")
        }

        assertThat(gw.resolve(platform)).isNull()
    }

    // --- resolve: BYO ---

    @Test
    fun `XENDIT BYO lengkap meresolusi dengan secret tenant`() {
        val gw = defaultGateway().apply {
            update(PaymentProvider.XENDIT, GatewayMode.BYO, enabled = true, apiKey = null, secretKey = "xnd_key", webhookToken = "tok")
        }

        val ctx = gw.resolve(platform)
        assertThat(ctx).isNotNull
        assertThat(ctx!!.provider).isEqualTo("XENDIT")
        assertThat(ctx.mode).isEqualTo(GatewayMode.BYO)
        assertThat(ctx.secretKey).isEqualTo("xnd_key")
        assertThat(ctx.webhookToken).isEqualTo("tok")
        assertThat(ctx.subAccountId).isNull()
    }

    @Test
    fun `XENDIT BYO tanpa secret meresolusi null walau aktif`() {
        val gw = defaultGateway().apply {
            update(PaymentProvider.XENDIT, GatewayMode.BYO, enabled = true, apiKey = null, secretKey = null, webhookToken = "tok")
        }

        assertThat(gw.resolve(platform)).isNull()
    }

    @Test
    fun `PIVOT BYO membawa merchant id dan secret terpisah plus callback key`() {
        val gw = defaultGateway().apply {
            update(PaymentProvider.PIVOT, GatewayMode.BYO, enabled = true, apiKey = "merchant_1", secretKey = "secret_1", webhookToken = "cb_key")
        }

        val ctx = gw.resolve(platform)
        assertThat(ctx).isNotNull
        assertThat(ctx!!.provider).isEqualTo("PIVOT")
        assertThat(ctx.apiKey).isEqualTo("merchant_1")     // → X-MERCHANT-ID
        assertThat(ctx.secretKey).isEqualTo("secret_1")    // → X-MERCHANT-SECRET
        assertThat(ctx.webhookToken).isEqualTo("cb_key")   // → X-API-Key callback
    }

    @Test
    fun `skeleton PAYWUZ tetap meresolusi agar adapternya dipilih dan melempar jelas`() {
        val gw = defaultGateway().apply {
            update(PaymentProvider.PAYWUZ, GatewayMode.BYO, enabled = true, apiKey = "pk_x", secretKey = null, webhookToken = "tok")
        }

        val ctx = gw.resolve(platform)
        assertThat(ctx).isNotNull
        assertThat(ctx!!.provider).isEqualTo("PAYWUZ")
    }

    @Test
    fun `MANUAL aktif meresolusi tanpa secret`() {
        val gw = defaultGateway().apply {
            update(PaymentProvider.MANUAL, GatewayMode.BYO, enabled = true, apiKey = null, secretKey = null, webhookToken = "tok")
        }

        val ctx = gw.resolve(platform)
        assertThat(ctx).isNotNull
        assertThat(ctx!!.provider).isEqualTo("MANUAL")
        assertThat(ctx.secretKey).isNull()
    }

    // --- resolve: PLATFORM ---

    @Test
    fun `PLATFORM lengkap memakai secret master dan header sub-account plus fee`() {
        val gw = provisionedPlatformGateway(subToken = "sub_tok")

        val ctx = gw.resolve(platform)
        assertThat(ctx).isNotNull
        assertThat(ctx!!.provider).isEqualTo("XENDIT")
        assertThat(ctx.mode).isEqualTo(GatewayMode.PLATFORM)
        assertThat(ctx.secretKey).isEqualTo("xnd_master") // kunci MASTER, bukan tenant
        assertThat(ctx.subAccountId).isEqualTo("acc_123")
        assertThat(ctx.feeRuleId).isEqualTo("fee_1")
        assertThat(ctx.webhookToken).isEqualTo("sub_tok") // token sub-account menang
    }

    @Test
    fun `PLATFORM tanpa token sub-account jatuh ke token platform global`() {
        val gw = provisionedPlatformGateway(subToken = null)

        assertThat(gw.resolve(platform)!!.webhookToken).isEqualTo("plat_tok")
    }

    @Test
    fun `PLATFORM meresolusi null saat platform nonaktif (creds null)`() {
        val gw = provisionedPlatformGateway(subToken = "sub_tok")

        // platform=null meniru ftth.billing.platform.enabled=false → dormant, jatuh ke fallback MANUAL.
        assertThat(gw.resolve(null)).isNull()
    }

    // --- update: secret kosong = pertahankan, validasi PLATFORM butuh sub-account ---

    @Test
    fun `secret kosong atau null saat update mempertahankan yang tersimpan`() {
        val gw = defaultGateway().apply {
            update(PaymentProvider.XENDIT, GatewayMode.BYO, enabled = true, apiKey = "pk_awal", secretKey = "sk_awal", webhookToken = "tok_awal")
        }

        gw.update(PaymentProvider.XENDIT, GatewayMode.BYO, enabled = true, apiKey = "   ", secretKey = null, webhookToken = "  ")

        assertThat(gw.apiKey).isEqualTo("pk_awal")
        assertThat(gw.secretKey).isEqualTo("sk_awal")
        assertThat(gw.webhookToken).isEqualTo("tok_awal")
    }

    @Test
    fun `secret baru saat update menimpa yang lama`() {
        val gw = defaultGateway().apply {
            update(PaymentProvider.XENDIT, GatewayMode.BYO, enabled = true, apiKey = null, secretKey = "sk_awal", webhookToken = null)
        }

        gw.update(PaymentProvider.XENDIT, GatewayMode.BYO, enabled = true, apiKey = null, secretKey = "sk_baru", webhookToken = null)

        assertThat(gw.secretKey).isEqualTo("sk_baru")
    }

    @Test
    fun `operator memilih PLATFORM tanpa sub-account ditolak`() {
        val gw = defaultGateway()

        assertThatThrownBy {
            gw.update(PaymentProvider.XENDIT, GatewayMode.PLATFORM, enabled = true, apiKey = null, secretKey = null, webhookToken = null)
        }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `provisionPlatform mengunci XENDIT PLATFORM aktif plus sub-account`() {
        val gw = defaultGateway()

        gw.provisionPlatform(subAccountId = "acc_9", webhookToken = "sub_tok")

        assertThat(gw.provider).isEqualTo(PaymentProvider.XENDIT)
        assertThat(gw.mode).isEqualTo(GatewayMode.PLATFORM)
        assertThat(gw.enabled).isTrue()
        assertThat(gw.subAccountId).isEqualTo("acc_9")
        assertThat(gw.webhookToken).isEqualTo("sub_tok")
    }

    @Test
    fun `provisionPlatform menolak sub-account kosong`() {
        assertThatThrownBy {
            defaultGateway().provisionPlatform(subAccountId = "  ", webhookToken = null)
        }.isInstanceOf(ValidationException::class.java)
    }

    // --- perkakas uji ---

    private fun defaultGateway() = TenantPaymentGateway.defaultFor(UuidV7.generate())

    private fun provisionedPlatformGateway(subToken: String?) = defaultGateway().apply {
        provisionPlatform(subAccountId = "acc_123", webhookToken = subToken)
    }
}
