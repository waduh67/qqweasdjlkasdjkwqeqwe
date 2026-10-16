package com.karuhun.ftth.platformbilling.application.service

import com.karuhun.ftth.common.infrastructure.audit.AuditRecorder
import com.karuhun.ftth.platformbilling.application.port.inbound.ProvisionTenantSubscriptionUseCase
import com.karuhun.ftth.platformbilling.application.port.outbound.PlatformSettingRepository
import com.karuhun.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.karuhun.ftth.platformbilling.domain.model.PlatformSetting
import com.karuhun.ftth.platformbilling.domain.model.TenantSubscription
import com.karuhun.ftth.tenancy.TenantApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Membuat langganan SaaS otomatis saat tenant baru di-onboard (dipanggil modul iam).
 * Harga = override super-admin bila diisi, jika null pakai harga default global ([PlatformSetting]).
 * Idempotent: tenant yang sudah punya langganan dilewati agar tak menimpa harga berjalan.
 */
@Service
class TenantSubscriptionProvisioningService(
    private val subscriptionRepository: TenantSubscriptionRepository,
    private val settingRepository: PlatformSettingRepository,
    private val tenantApi: TenantApi,
    private val auditor: AuditRecorder,
) : ProvisionTenantSubscriptionUseCase {

    @Transactional
    override fun ensureForTenant(tenantId: UUID, monthlyFeeOverride: BigDecimal?) {
        if (subscriptionRepository.findByTenantId(tenantId) != null) return
        val defaultFee = (settingRepository.find() ?: PlatformSetting.default()).defaultMonthlyFee
        val monthlyFee = monthlyFeeOverride ?: defaultFee
        val subscription = TenantSubscription.create(tenantId = tenantId, monthlyFee = monthlyFee).apply {
            // Beri masa aktif awal sebulan; tagihan pertama terbit menjelang periode habis (bukan seketika).
            seedInitialPeriod(LocalDate.now())
        }
        val saved = subscriptionRepository.save(subscription)
        auditor.record(
            action = "platform.subscription.provisioned",
            entityType = "TenantSubscription",
            entityId = saved.id,
            tenantId = tenantApi.platformTenantId(),
            detail = mapOf(
                "tenantId" to tenantId.toString(),
                "monthlyFee" to saved.monthlyFee.toPlainString(),
                "override" to (monthlyFeeOverride != null),
            ),
        )
    }
}
