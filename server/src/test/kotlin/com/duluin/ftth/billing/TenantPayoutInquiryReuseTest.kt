package com.duluin.ftth.billing

import com.duluin.ftth.billing.application.port.inbound.DispatchPayoutCommand
import com.duluin.ftth.billing.application.port.outbound.BalanceSnapshot
import com.duluin.ftth.billing.application.port.outbound.InquiryResult
import com.duluin.ftth.billing.application.port.outbound.InquiryStatus
import com.duluin.ftth.billing.application.port.outbound.PayoutCommand
import com.duluin.ftth.billing.application.port.outbound.PayoutDispatch
import com.duluin.ftth.billing.application.port.outbound.PivotBalanceUsecase
import com.duluin.ftth.billing.application.port.outbound.PivotMasterConfigRepository
import com.duluin.ftth.billing.application.port.outbound.PivotPayoutPort
import com.duluin.ftth.billing.application.port.outbound.PivotSubMerchantPort
import com.duluin.ftth.billing.application.port.outbound.SubMerchantCreateRequest
import com.duluin.ftth.billing.application.port.outbound.SubMerchantResult
import com.duluin.ftth.billing.application.port.outbound.TenantPayoutRepository
import com.duluin.ftth.billing.application.port.outbound.TenantPivotAccountRepository
import com.duluin.ftth.billing.application.service.PivotMasterConfigProvider
import com.duluin.ftth.billing.application.service.TenantPayoutService
import com.duluin.ftth.billing.domain.model.PivotFeeType
import com.duluin.ftth.billing.domain.model.PivotMasterConfig
import com.duluin.ftth.billing.domain.model.PivotMasterContext
import com.duluin.ftth.billing.domain.model.SubAccountDefaults
import com.duluin.ftth.billing.domain.model.SubAccountKycStatus
import com.duluin.ftth.billing.domain.model.SubAccountStatus
import com.duluin.ftth.billing.domain.model.SubAccountType
import com.duluin.ftth.billing.domain.model.TenantPayout
import com.duluin.ftth.billing.domain.model.TenantPivotAccount
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

/**
 * `POST /v1/inquiry-account` ditagih Rp 450 per panggilan ke saldo DISBURSEMENT master — termasuk
 * untuk rekening yang itu-itu juga (Pivot mengembalikan uuid yang sama), dan termasuk saat hasilnya
 * ditolak. Jadi `inquiryId` disimpan di `tenant_pivot_account` dan dipakai ulang; inquiry baru hanya
 * ditembak bila rekening tujuannya benar-benar berubah.
 *
 * Pakai fake in-memory + [TenantContext] manual, tanpa Spring/DB/HTTP (pola [TenantPayoutBalanceTest]).
 */
class TenantPayoutInquiryReuseTest {

    private val tenantId = UuidV7.generate()
    private val subId = "93001fc5-6137-4c96-be35-7029576b9d68"

    private lateinit var subMerchant: CountingSubMerchantPort
    private lateinit var accounts: FakeAccountRepository
    private lateinit var payoutPort: FundedPayoutPort
    private lateinit var service: TenantPayoutService

    @BeforeEach
    fun setUp() {
        TenantContext.set(tenantId)
        subMerchant = CountingSubMerchantPort()
        accounts = FakeAccountRepository(provisionedAccount())
        payoutPort = FundedPayoutPort()
        service = TenantPayoutService(
            repository = FakePayoutRepository(),
            accounts = accounts,
            masterConfig = PivotMasterConfigProvider(FakeMasterRepository()),
            payoutPort = payoutPort,
            subMerchant = subMerchant,
            auditor = AuditRecorder(ApplicationEventPublisher { }, NoUser),
        )
    }

    @AfterEach
    fun tearDown() = TenantContext.clear()

    private fun dispatch(
        channelCode: String = "BCA",
        accountNumber: String = "999966660001",
        accountName: String = "Dummy Simulation",
    ) = service.dispatchPayout(
        DispatchPayoutCommand(
            channelCode = channelCode,
            accountNumber = accountNumber,
            accountName = accountName,
            amountMinor = 50_000,
            description = "Payout uji",
        ),
    )

    @Test
    fun `payout pertama menembak inquiry dan menyimpan hasilnya`() {
        dispatch()

        assertThat(subMerchant.calls).isEqualTo(1)
        val saved = accounts.find()
        assertThat(saved.payoutInquiryId).isEqualTo("inq-1")
        assertThat(saved.payoutChannelCode).isEqualTo("BCA")
        assertThat(saved.payoutAccountNumber).isEqualTo("999966660001")
        assertThat(saved.payoutAccountName).isEqualTo("Dummy Simulation")
    }

    @Test
    fun `payout berikutnya ke rekening yang sama tak menembak inquiry lagi`() {
        dispatch()
        dispatch()
        dispatch()

        // Tiga payout, satu inquiry — dua panggilan @Rp 450 yang dihemat.
        assertThat(subMerchant.calls).isEqualTo(1)
    }

    @Test
    fun `payout kedua mengirim inquiryId tersimpan ke Pivot`() {
        dispatch()
        dispatch()

        // Bukan sekadar tak menembak inquiry — payoutnya harus tetap membawa id yang sah.
        assertThat(payoutPort.dispatched.map { it.inquiryId }).containsExactly("inq-1", "inq-1")
    }

    @Test
    fun `ganti nomor rekening memaksa inquiry ulang`() {
        dispatch(accountNumber = "999966660001")
        dispatch(accountNumber = "999966660002")

        assertThat(subMerchant.calls).isEqualTo(2)
        assertThat(accounts.find().payoutAccountNumber).isEqualTo("999966660002")
    }

    @Test
    fun `ganti bank memaksa inquiry ulang`() {
        dispatch(channelCode = "BCA")
        dispatch(channelCode = "BRI")

        assertThat(subMerchant.calls).isEqualTo(2)
    }

    @Test
    fun `ganti nama pemilik memaksa inquiry ulang`() {
        // Pivot mencocokkan nama dengan catatan bank, jadi hasil validasi lama tak berlaku lagi.
        dispatch(accountName = "Dummy Simulation")
        dispatch(accountName = "Nama Lain")

        assertThat(subMerchant.calls).isEqualTo(2)
    }

    @Test
    fun `hasil inquiry yang ditolak tak disimpan, jadi tak ikut dipakai ulang`() {
        subMerchant.status = InquiryStatus.WARNING

        assertThatThrownBy { dispatch() }.isInstanceOf(ConflictException::class.java)

        assertThat(accounts.find().payoutInquiryId).isNull()
        // Percobaan berikutnya harus menembak inquiry lagi — bukan memakai hasil yang gagal.
        assertThatThrownBy { dispatch() }.isInstanceOf(ConflictException::class.java)
        assertThat(subMerchant.calls).isEqualTo(2)
    }

    // --- fakes ---

    private fun provisionedAccount() = TenantPivotAccount.defaultFor(tenantId).apply {
        markProvisioned(subId, SubAccountType.NON_KYC, SubAccountStatus.ACTIVE, SubAccountKycStatus.NOT_REQUIRED)
    }

    private class CountingSubMerchantPort : PivotSubMerchantPort {
        var calls = 0
        var status = InquiryStatus.VALID

        override fun inquiryAccount(
            master: PivotMasterContext,
            subMerchantId: String,
            channelCode: String,
            accountNumber: String,
            accountName: String,
        ): InquiryResult {
            calls++
            return InquiryResult(inquiryId = "inq-$calls", status = status, detail = "detail uji")
        }

        override fun create(master: PivotMasterContext, request: SubMerchantCreateRequest): SubMerchantResult =
            error("tak dipakai")

        override fun fetch(master: PivotMasterContext, subMerchantUuid: String): SubMerchantResult =
            error("tak dipakai")

        override fun assignUser(master: PivotMasterContext, subMerchantId: String, email: String, name: String) = Unit

        override fun resendInvitation(master: PivotMasterContext, subMerchantId: String, email: String) = Unit
    }

    /** Saldo payout selalu cukup — uji ini soal inquiry, bukan jembatan dompet. */
    private class FundedPayoutPort : PivotPayoutPort {
        val dispatched = mutableListOf<PayoutCommand>()

        override fun payout(
            master: PivotMasterContext,
            subMerchantId: String,
            command: PayoutCommand,
            requestId: String,
        ): PayoutDispatch {
            dispatched += command
            return PayoutDispatch(reference = "pv-${dispatched.size}", settledImmediately = false)
        }

        override fun withdraw(
            master: PivotMasterContext,
            subMerchantId: String,
            command: PayoutCommand,
            requestId: String,
        ): PayoutDispatch = PayoutDispatch(reference = "wd", settledImmediately = false)

        override fun transferToPayoutBalance(
            master: PivotMasterContext,
            subMerchantId: String,
            amountMinor: Long,
            referenceId: String,
            description: String?,
            requestId: String,
        ) = Unit

        override fun transferToMaster(
            master: PivotMasterContext,
            subMerchantId: String,
            amountMinor: Long,
            referenceId: String,
            remarks: String,
            requestId: String,
        ) = Unit

        override fun balance(
            master: PivotMasterContext,
            subMerchantId: String?,
            usecase: PivotBalanceUsecase,
        ): BalanceSnapshot = BalanceSnapshot(10_000_000, "IDR")
    }

    private class FakePayoutRepository : TenantPayoutRepository {
        override fun save(payout: TenantPayout): TenantPayout = payout
        override fun list(): List<TenantPayout> = emptyList()
        override fun findByReference(reference: String): TenantPayout? = null
    }

    /** Menyimpan sungguhan — inti ujinya justru apakah hasil inquiry bertahan antar-payout. */
    private class FakeAccountRepository(private var account: TenantPivotAccount) : TenantPivotAccountRepository {
        override fun find(): TenantPivotAccount = account

        override fun save(account: TenantPivotAccount): TenantPivotAccount {
            this.account = account
            return account
        }

        override fun findByTenant(tenantId: UUID): TenantPivotAccount = account
    }

    private class FakeMasterRepository : PivotMasterConfigRepository {
        override fun find(): PivotMasterConfig = PivotMasterConfig.default().apply {
            update(
                enabled = true,
                merchantId = "master-id",
                merchantSecret = "master-secret",
                callbackApiKey = "cb",
                sandbox = true,
                platformFeeMinor = 0,
                platformFeeType = PivotFeeType.FIXED,
                payoutFeeMinor = 0,
                payoutFeeType = PivotFeeType.FIXED,
                payoutChannelCode = null,
                payoutAccountNumber = null,
                subAccountDefaults = SubAccountDefaults(
                    businessType = null,
                    businessStructure = null,
                    parentIndustry = null,
                    childIndustry = null,
                    mcc = null,
                    digitalStatus = null,
                    businessCountry = null,
                    countryOfEntity = null,
                    logoUrl = null,
                    website = null,
                    districtId = null,
                    postCode = null,
                ),
            )
        }

        override fun save(config: PivotMasterConfig): PivotMasterConfig = config
    }

    private object NoUser : CurrentUserProvider {
        override fun currentOrNull(): AuthenticatedUser? = null
    }
}
