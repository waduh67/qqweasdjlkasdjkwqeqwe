package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.inbound.ManageTenantPivotAccountUseCase
import com.duluin.ftth.billing.application.port.inbound.ProvisionTenantPivotAccountUseCase
import com.duluin.ftth.billing.application.port.inbound.SetPivotPayoutAccountCommand
import com.duluin.ftth.billing.application.port.inbound.TenantPivotAccountView
import com.duluin.ftth.billing.application.port.outbound.PivotSubMerchantPort
import com.duluin.ftth.billing.application.port.outbound.TenantPivotAccountRepository
import com.duluin.ftth.billing.domain.model.PivotMasterContext
import com.duluin.ftth.billing.domain.model.SubAccountType
import com.duluin.ftth.billing.domain.model.TenantPivotAccount
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Manajemen sub-account Pivot tenant: provisioning (otomatis saat onboarding + manual), sinkronisasi
 * status, upgrade KYC, dan setelan rekening payout. Sisi operator (`/payment-gateway`) memakai
 * [ManageTenantPivotAccountUseCase]; onboarding/backfill memakai [ProvisionTenantPivotAccountUseCase].
 *
 * Semua charge tenant berjalan on-behalf-of sub-account ini (lihat [TenantPaymentGatewayResolver]);
 * tanpa sub-account terprovisi, resolver jatuh ke MANUAL. Perubahan dicatat ke jejak audit — rekening
 * payout menentukan ke mana dana NON_KYC tenant disalurkan.
 */
@Service
@Transactional(readOnly = true)
class TenantPivotAccountService(
    private val repository: TenantPivotAccountRepository,
    private val masterConfig: PivotMasterConfigProvider,
    private val subMerchant: PivotSubMerchantPort,
    private val tenantApi: TenantApi,
    private val auditor: AuditRecorder,
) : ManageTenantPivotAccountUseCase, ProvisionTenantPivotAccountUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun get(): TenantPivotAccountView {
        val account = repository.find() ?: TenantPivotAccount.defaultFor(TenantContext.tenantId())
        return account.toView()
    }

    @Transactional
    override fun provision(): TenantPivotAccountView {
        val master = requireMaster()
        val tenantId = TenantContext.tenantId()
        val account = repository.find() ?: TenantPivotAccount.defaultFor(tenantId)
        if (account.provisioned) return account.toView()
        return provisionNonKyc(account, tenantId, master).toView()
    }

    @Transactional
    override fun ensureForTenant(tenantId: UUID) {
        val master = masterConfig.current()
        if (master == null) {
            log.info("Master Pivot belum aktif — lewati provisioning sub-account tenant {}", tenantId)
            return
        }
        // Dijalankan dalam TenantContext.runAs(tenantId) oleh pemanggil (listener) → RLS & @TenantId benar.
        val account = repository.find() ?: TenantPivotAccount.defaultFor(tenantId)
        if (account.provisioned) return
        provisionNonKyc(account, tenantId, master)
    }

    @Transactional
    override fun refreshStatus(): TenantPivotAccountView {
        val master = requireMaster()
        val account = repository.find() ?: return TenantPivotAccount.defaultFor(TenantContext.tenantId()).toView()
        val uuid = account.subMerchantUuid ?: return account.toView()
        val result = subMerchant.fetch(master, uuid)
        account.applyStatus(result.status, result.kycStatus)
        return repository.save(account).toView()
    }

    @Transactional
    override fun requestKyc(): TenantPivotAccountView {
        val master = requireMaster()
        val tenantId = TenantContext.tenantId()
        val account = repository.find() ?: TenantPivotAccount.defaultFor(tenantId)
        val tenant = tenantApi.requireById(tenantId)
        val shortName = account.shortName ?: descriptorFor(tenant.name)
        // KYC = sub-account atas nama tenant sendiri: buat baru bertipe KYC, dokumen dikirim
        // out-of-band ke verification@pivot-payment.com (di luar app) untuk approval Pivot.
        val result = subMerchant.create(master, SubAccountType.KYC, shortName, tenant.name)
        account.setShortName(shortName)
        account.markProvisioned(result.subMerchantUuid, SubAccountType.KYC, result.status, result.kycStatus)
        account.requestKyc()
        val saved = repository.save(account)
        audit("billing.pivot.kyc.requested", saved.id, tenantId)
        return saved.toView()
    }

    @Transactional
    override fun setPayoutAccount(command: SetPivotPayoutAccountCommand): TenantPivotAccountView {
        val master = requireMaster()
        val channelCode = command.channelCode.trim().uppercase().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Channel bank wajib diisi")
        val accountNumber = command.accountNumber.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Nomor rekening wajib diisi")
        val tenantId = TenantContext.tenantId()
        val account = repository.find() ?: TenantPivotAccount.defaultFor(tenantId)
        val inquiry = subMerchant.inquiryAccount(master, channelCode, accountNumber)
        account.setPayoutAccount(channelCode, accountNumber, inquiry.accountName, inquiry.inquiryId)
        val saved = repository.save(account)
        audit("billing.pivot.payout.updated", saved.id, tenantId)
        return saved.toView()
    }

    private fun provisionNonKyc(
        account: TenantPivotAccount,
        tenantId: UUID,
        master: PivotMasterContext,
    ): TenantPivotAccount {
        val tenant = tenantApi.requireById(tenantId)
        val shortName = account.shortName ?: descriptorFor(tenant.name)
        val result = subMerchant.create(master, SubAccountType.NON_KYC, shortName, tenant.name)
        account.setShortName(shortName)
        account.markProvisioned(result.subMerchantUuid, SubAccountType.NON_KYC, result.status, result.kycStatus)
        val saved = repository.save(account)
        audit("billing.pivot.provisioned", saved.id, tenantId)
        log.info("Sub-account Pivot NON_KYC dibuat untuk tenant {} (uuid {})", tenantId, result.subMerchantUuid)
        return saved
    }

    private fun requireMaster(): PivotMasterContext = masterConfig.current()
        ?: throw ConflictException("Pivot belum diaktifkan platform — sub-account tak bisa dikelola sekarang")

    private fun audit(action: String, entityId: UUID, tenantId: UUID) = auditor.record(
        action = action,
        entityType = "TenantPivotAccount",
        entityId = entityId,
        tenantId = tenantId,
    )

    private fun TenantPivotAccount.toView() = TenantPivotAccountView(
        provisioned = provisioned,
        type = type,
        status = status,
        kycStatus = kycStatus,
        shortName = shortName,
        payoutChannelCode = payoutChannelCode,
        payoutAccountNumber = payoutAccountNumber,
        payoutAccountName = payoutAccountName,
        payoutReady = payoutReady,
        masterActive = masterConfig.current() != null,
    )

    private companion object {
        const val MAX_DESCRIPTOR = 20

        /** Transaction descriptor dari nama tenant: huruf/angka/spasi, ringkas, fallback `FTTH`. */
        fun descriptorFor(name: String): String = name
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_DESCRIPTOR)
            .ifBlank { "FTTH" }
    }
}
