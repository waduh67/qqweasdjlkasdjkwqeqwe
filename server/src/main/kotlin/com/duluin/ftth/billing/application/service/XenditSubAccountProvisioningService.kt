package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.inbound.ProvisionSubAccountCommand
import com.duluin.ftth.billing.application.port.inbound.ProvisionSubAccountUseCase
import com.duluin.ftth.billing.application.port.inbound.SubAccountProvisionResult
import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.application.port.outbound.XenditPlatformClient
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Provisioning sub-account Xendit (mode PLATFORM) untuk satu tenant. Alurnya dua fase yang
 * sengaja dipisah:
 *
 *  1. Panggilan HTTP ke akun MASTER (buat sub-account + arahkan callback) — di LUAR transaksi DB
 *     & di luar tenant context, karena memakai kredensial platform, bukan baris tenant.
 *  2. Simpan hasilnya ke baris gateway tenant sasaran DI DALAM [TenantContext.runAs] (patuh RLS),
 *     lewat [TenantGatewayProvisionPersister] terpisah agar `@Transactional` benar-benar berlaku
 *     (proxy Spring tak membungkus pemanggilan sesama-kelas) dan transaksi terbuka setelah tenant
 *     terpasang — pola sama dengan `AutoProvisionScheduler`/`AutoProvisioner`.
 */
@Service
class XenditSubAccountProvisioningService(
    private val platformClient: XenditPlatformClient,
    private val tenantApi: TenantApi,
    private val persister: TenantGatewayProvisionPersister,
    private val properties: BillingProperties,
) : ProvisionSubAccountUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun provisionXendit(command: ProvisionSubAccountCommand): SubAccountProvisionResult {
        val tenant = tenantApi.requireById(command.tenantId)
        val businessName = command.businessName?.trim()?.takeIf { it.isNotEmpty() } ?: tenant.name

        val subAccount = platformClient.createManagedSubAccount(command.email.trim(), businessName)

        // Arahkan callback invoice sub-account ke path per-slug kita bila base URL publik diset.
        // Best-effort: sub-account sudah terlanjur ada — jangan anulir hanya karena set callback gagal.
        val callbackBaseUrl = properties.platform.xendit.callbackBaseUrl.trim().removeSuffix("/")
        if (callbackBaseUrl.isNotEmpty()) {
            val callbackUrl = "$callbackBaseUrl/api/billing/webhooks/${tenant.slug}/xendit"
            runCatching { platformClient.setInvoiceCallbackUrl(subAccount.userId, callbackUrl) }
                .onFailure { log.warn("Set callback URL sub-account {} gagal: {}", subAccount.userId, it.message) }
        } else {
            log.warn("callback-base-url platform kosong — callback sub-account {} belum diarahkan, mengandalkan token platform global", subAccount.userId)
        }

        TenantContext.runAs(command.tenantId) {
            persister.persist(command.tenantId, subAccount.userId, subAccount.callbackToken)
        }
        return SubAccountProvisionResult(
            tenantId = command.tenantId,
            subAccountId = subAccount.userId,
            callbackTokenSet = !subAccount.callbackToken.isNullOrBlank(),
        )
    }
}

/**
 * Menyimpan hasil provisioning ke baris gateway tenant sasaran, di transaksinya sendiri yang
 * terbuka SETELAH [TenantContext.runAs] memasang tenant (RLS memfilter baris ke tenant itu).
 * Bean terpisah agar proxy `@Transactional` aktif — bukan self-invocation dari service koordinator.
 */
@Service
class TenantGatewayProvisionPersister(
    private val repository: TenantPaymentGatewayRepository,
    private val auditor: AuditRecorder,
) {
    @Transactional
    fun persist(tenantId: UUID, subAccountId: String, callbackToken: String?) {
        val settings = repository.find() ?: TenantPaymentGateway.defaultFor(tenantId)
        settings.provisionPlatform(subAccountId, callbackToken)
        val saved = repository.save(settings)
        auditor.record(
            action = "billing.gateway.provisioned",
            entityType = "TenantPaymentGateway",
            entityId = saved.id,
            tenantId = saved.tenantId,
            detail = mapOf("provider" to "XENDIT", "mode" to "PLATFORM", "subAccountId" to subAccountId),
        )
    }
}
