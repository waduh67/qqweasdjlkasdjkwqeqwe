package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.inbound.AssignPivotUserCommand
import com.duluin.ftth.billing.application.port.inbound.ManageTenantPivotAccountUseCase
import com.duluin.ftth.billing.application.port.inbound.ProvisionTenantPivotAccountUseCase
import com.duluin.ftth.billing.application.port.inbound.ResendPivotInvitationCommand
import com.duluin.ftth.billing.application.port.inbound.SaveTenantPivotProfileCommand
import com.duluin.ftth.billing.application.port.inbound.SetPivotPayoutAccountCommand
import com.duluin.ftth.billing.application.port.inbound.TenantPivotAccountView
import com.duluin.ftth.billing.application.port.outbound.PivotSubMerchantPort
import com.duluin.ftth.billing.application.port.outbound.SubMerchantCreateRequest
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
        val request = buildCreateRequest(account, tenant.name, shortName, SubAccountType.KYC, master)
        val result = subMerchant.create(master, request)
        account.setShortName(shortName)
        account.markProvisioned(result.subMerchantUuid, SubAccountType.KYC, result.status, result.kycStatus)
        account.requestKyc()
        val saved = repository.save(account)
        audit("billing.pivot.kyc.requested", saved.id, tenantId)
        return saved.toView()
    }

    @Transactional
    override fun saveProfile(command: SaveTenantPivotProfileCommand): TenantPivotAccountView {
        val tenantId = TenantContext.tenantId()
        val account = repository.find() ?: TenantPivotAccount.defaultFor(tenantId)
        account.setProfile(
            legalName = command.legalName,
            merchantEmail = command.merchantEmail,
            merchantPhone = command.merchantPhone,
            picName = command.picName,
            picEmail = command.picEmail,
            picPhone = command.picPhone,
            address = command.address,
        )
        // Rekening payout kini bagian dari profil (Pivot mewajibkan `bankAccount` saat create), tapi
        // hanya DISIMPAN di sini — TANPA inquiry. `POST /v1/inquiry-account` baru bisa jalan setelah
        // sub-account ada, jadi validasi rekening dijalankan best-effort saat provisioning (lihat
        // provisionNonKyc). Simpan profil murni persistensi lokal → tak pernah menembak Pivot / error.
        account.setPayoutDestination(command.channelCode, command.accountNumber)
        val saved = repository.save(account)
        audit("billing.pivot.profile.updated", saved.id, tenantId)
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

    @Transactional
    override fun assignUser(command: AssignPivotUserCommand): TenantPivotAccountView {
        val master = requireMaster()
        val tenantId = TenantContext.tenantId()
        val account = repository.find() ?: TenantPivotAccount.defaultFor(tenantId)
        val subId = requireProvisioned(account)
        val email = command.email.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Email pengguna wajib diisi")
        val name = command.name.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Nama pengguna wajib diisi")
        subMerchant.assignUser(master, subId, email, name)
        audit("billing.pivot.user.assigned", account.id, tenantId)
        log.info("User '{}' diundang ke sub-account tenant {}", email, tenantId)
        return account.toView()
    }

    @Transactional
    override fun resendInvitation(command: ResendPivotInvitationCommand): TenantPivotAccountView {
        val master = requireMaster()
        val tenantId = TenantContext.tenantId()
        val account = repository.find() ?: TenantPivotAccount.defaultFor(tenantId)
        val subId = requireProvisioned(account)
        val email = command.email.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Email pengguna wajib diisi")
        subMerchant.resendInvitation(master, subId, email)
        audit("billing.pivot.invitation.resent", account.id, tenantId)
        log.info("Undangan dikirim ulang ke '{}' untuk sub-account tenant {}", email, tenantId)
        return account.toView()
    }

    /** UUID sub-account tenant — aksi on-behalf butuh sub-account sudah terdaftar di Pivot. */
    private fun requireProvisioned(account: TenantPivotAccount): String = account.subMerchantUuid
        ?: throw ConflictException("Sub-account belum terdaftar di Pivot — daftarkan dulu")

    private fun provisionNonKyc(
        account: TenantPivotAccount,
        tenantId: UUID,
        master: PivotMasterContext,
    ): TenantPivotAccount {
        val tenant = tenantApi.requireById(tenantId)
        val shortName = account.shortName ?: descriptorFor(tenant.name)
        val request = buildCreateRequest(account, tenant.name, shortName, SubAccountType.NON_KYC, master)
        val result = subMerchant.create(master, request)
        account.setShortName(shortName)
        account.markProvisioned(result.subMerchantUuid, SubAccountType.NON_KYC, result.status, result.kycStatus)
        // Sub-account sudah ada → sekarang inquiry rekening bisa jalan. Best-effort: isi
        // payoutAccountName + inquiryId (dipakai `POST /v1/payouts`). Kegagalannya TIDAK
        // menggagalkan provisioning — bisa diulang lewat "Simpan rekening" di UI.
        ensurePayoutInquiry(account, master)
        val saved = repository.save(account)
        audit("billing.pivot.provisioned", saved.id, tenantId)
        log.info("Sub-account Pivot NON_KYC dibuat untuk tenant {} (uuid {})", tenantId, result.subMerchantUuid)
        return saved
    }

    /**
     * Validasi rekening payout (`POST /v1/inquiry-account`) setelah sub-account ada, mengisi
     * `payoutAccountName` + `inquiryId`. Best-effort: bila rekening belum diisi atau Pivot menolak,
     * cukup log warning — payout tinggal "belum siap" & bisa diulang lewat setPayoutAccount.
     */
    private fun ensurePayoutInquiry(account: TenantPivotAccount, master: PivotMasterContext) {
        val channelCode = account.payoutChannelCode ?: return
        val accountNumber = account.payoutAccountNumber ?: return
        if (account.payoutReady) return
        try {
            val inquiry = subMerchant.inquiryAccount(master, channelCode, accountNumber)
            account.setPayoutAccount(channelCode, accountNumber, inquiry.accountName, inquiry.inquiryId)
        } catch (e: Exception) {
            log.warn(
                "Inquiry rekening payout gagal untuk tenant {} (channel {}): {} — payout belum siap, bisa diulang",
                account.tenantId, channelCode, e.message,
            )
        }
    }

    /**
     * Rakit payload create sub-account: gabung profil tenant (identitas/PIC/alamat + rekening) dengan
     * default level-platform (referensi bisnis/industri). Melempar [ValidationException] yang jelas
     * bila profil tenant belum lengkap ATAU default platform belum diisi — supaya request tak
     * dikirim setengah jadi lalu ditolak 400 oleh Pivot.
     */
    private fun buildCreateRequest(
        account: TenantPivotAccount,
        tenantName: String,
        shortName: String,
        type: SubAccountType,
        master: PivotMasterContext,
    ): SubMerchantCreateRequest {
        if (!account.profileComplete) {
            throw ValidationException(
                "Lengkapi profil sub-account dulu (email & telepon bisnis, nama/email/telepon PIC, " +
                    "alamat, rekening payout) sebelum mendaftar ke Pivot",
            )
        }
        val d = master.subAccountDefaults
        val missing = buildList {
            if (d.businessType.isNullOrBlank()) add("tipe bisnis")
            if (d.businessStructure.isNullOrBlank()) add("struktur bisnis")
            if (d.parentIndustry.isNullOrBlank()) add("industri induk")
            if (d.childIndustry.isNullOrBlank()) add("industri anak")
            if (d.mcc.isNullOrBlank()) add("MCC")
            if (d.digitalStatus.isNullOrBlank()) add("status digital")
            if (d.businessCountry.isNullOrBlank()) add("negara bisnis")
            if (d.countryOfEntity.isNullOrBlank()) add("negara entitas")
            if (d.logoUrl.isNullOrBlank()) add("logo")
            if (d.website.isNullOrBlank()) add("website")
            if (d.districtId == null) add("district ID")
            if (d.postCode.isNullOrBlank()) add("kode pos")
        }
        if (missing.isNotEmpty()) {
            throw ValidationException(
                "Default sub-account platform belum lengkap: ${missing.joinToString(", ")}. " +
                    "Isi di setelan Billing Langganan Platform sebelum tenant bisa mendaftar.",
            )
        }
        return SubMerchantCreateRequest(
            type = type,
            shortName = shortName,
            name = account.legalName ?: tenantName,
            website = d.website!!,
            logo = d.logoUrl!!,
            merchantEmail = account.merchantEmail!!,
            merchantPhone = account.merchantPhone!!,
            businessCountry = d.businessCountry!!,
            businessType = d.businessType!!,
            businessStructure = d.businessStructure!!,
            parentIndustry = d.parentIndustry!!,
            childIndustry = d.childIndustry!!,
            mcc = d.mcc!!,
            countryOfEntity = d.countryOfEntity!!,
            digitalStatus = d.digitalStatus!!,
            picName = account.picName!!,
            picEmail = account.picEmail!!,
            picPhone = account.picPhone!!,
            address = account.address!!,
            districtId = d.districtId!!,
            postCode = d.postCode!!,
            bankChannelCode = account.payoutChannelCode,
            bankAccountNumber = account.payoutAccountNumber,
        )
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
        subMerchantUuid = subMerchantUuid,
        type = type,
        status = status,
        kycStatus = kycStatus,
        shortName = shortName,
        legalName = legalName,
        merchantEmail = merchantEmail,
        merchantPhone = merchantPhone,
        picName = picName,
        picEmail = picEmail,
        picPhone = picPhone,
        address = address,
        profileComplete = profileComplete,
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
