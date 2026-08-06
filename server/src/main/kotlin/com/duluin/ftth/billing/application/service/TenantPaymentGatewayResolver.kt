package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.application.port.outbound.TenantPivotAccountRepository
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.billing.domain.model.SubAccountStatus
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Component

/**
 * Menyelesaikan gateway aktif untuk tenant saat ini. Model Pivot "business as platform":
 * bila tenant memakai PIVOT dan sub-account-nya sudah terprovisi (aktif), charge dibuat di akun
 * MASTER platform ([com.duluin.ftth.billing.domain.model.PivotMasterConfig]) atas nama sub-account
 * tenant (`x-submerchant-id`) plus split fee platform. Selain itu jatuh ke fallback MANUAL
 * (instruksi transfer/QRIS tenant + verifikasi webhook bersecret global).
 *
 * Dipakai baik saat penerbitan tagihan (charge) maupun callback webhook (verifikasi) — satu tempat
 * yang memetakan setelan mentah → bentuk siap-pakai, meniru langkah resolusi `NotificationSender`.
 */
@Component
class TenantPaymentGatewayResolver(
    private val repo: TenantPaymentGatewayRepository,
    private val subAccounts: TenantPivotAccountRepository,
    private val masterConfig: PivotMasterConfigProvider,
    private val tenantApi: TenantApi,
    private val props: BillingProperties,
) {
    fun resolve(): ResolvedGatewayContext {
        val settings = repo.find()
        if (settings != null && settings.usesPivot) {
            val master = masterConfig.current()
            val sub = subAccounts.find()?.takeIf { it.provisioned && it.status.usableForCharge() }
            if (master != null && sub?.subMerchantUuid != null) {
                return ResolvedGatewayContext(
                    provider = "PIVOT",
                    mode = GatewayMode.PLATFORM,
                    secretKey = master.merchantSecret,
                    webhookToken = master.callbackApiKey,
                    subAccountId = sub.subMerchantUuid,
                    tenantSlug = tenantApi.findById(TenantContext.tenantId())?.slug,
                    apiKey = master.merchantId,
                    sandbox = master.sandbox,
                    platformFeeMinor = master.platformFeeMinor,
                    platformFeeType = master.platformFeeType,
                )
            }
            // PIVOT dipilih tapi master nonaktif / sub-account belum siap → fallback MANUAL,
            // agar pelanggan tetap mendapat instruksi bayar (bukan charge yang pasti gagal).
        }
        return manualFallback()
    }

    /** Fallback MANUAL memakai shared secret global — perilaku lama sebelum ada config per-tenant. */
    private fun manualFallback() = ResolvedGatewayContext(
        provider = "MANUAL",
        mode = GatewayMode.BYO,
        secretKey = null,
        webhookToken = props.webhookSecret,
    )

    /** Sub-account bisa dipakai charge selama tidak dinonaktifkan/ditolak. */
    private fun SubAccountStatus.usableForCharge(): Boolean =
        this != SubAccountStatus.DEACTIVATED && this != SubAccountStatus.REJECTED
}
