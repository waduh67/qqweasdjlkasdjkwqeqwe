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
 * Jembatan dua dompet Pivot saat payout: `POST /v1/payouts` HANYA menarik dari saldo DISBURSEMENT,
 * sedangkan uang tenant mendarat di saldo PAYMENT. Service memindahkan kekurangannya lebih dulu
 * (`BALANCE_TRANSFER`) — tapi **hanya bila perlu**.
 *
 * Pakai fake in-memory + [TenantContext] manual, tanpa Spring/DB/HTTP (pola
 * `PaymentGatewaySettingsServiceTest`).
 */
class TenantPayoutBalanceTest {

    private val tenantId = UuidV7.generate()
    private val subId = "93001fc5-6137-4c96-be35-7029576b9d68"

    private lateinit var payoutPort: FakePayoutPort
    private lateinit var service: TenantPayoutService

    @BeforeEach
    fun setUp() {
        TenantContext.set(tenantId)
        payoutPort = FakePayoutPort()
        service = TenantPayoutService(
            repository = FakePayoutRepository(),
            accounts = FakeAccountRepository(provisionedAccount()),
            masterConfig = PivotMasterConfigProvider(FakeMasterRepository()),
            payoutPort = payoutPort,
            subMerchant = FakeSubMerchantPort(),
            auditor = AuditRecorder(ApplicationEventPublisher { }, NoUser),
        )
    }

    @AfterEach
    fun tearDown() = TenantContext.clear()

    private fun dispatch(amount: Long) = service.dispatchPayout(
        DispatchPayoutCommand(
            channelCode = "BCA",
            accountNumber = "999966660001",
            accountName = "Dummy Simulation",
            amountMinor = amount,
            description = "Payout uji",
        ),
    )

    @Test
    fun `saldo payout cukup - tak ada pemindahan saldo sama sekali`() {
        payoutPort.balances[PivotBalanceUsecase.DISBURSEMENT] = 500_000
        payoutPort.balances[PivotBalanceUsecase.PAYMENT] = 856_600

        dispatch(500_000)

        assertThat(payoutPort.transfers).isEmpty()
        assertThat(payoutPort.dispatched).hasSize(1)
    }

    @Test
    fun `saldo payout kurang - hanya kekurangannya yang dipindahkan`() {
        payoutPort.balances[PivotBalanceUsecase.DISBURSEMENT] = 120_000
        payoutPort.balances[PivotBalanceUsecase.PAYMENT] = 856_600

        dispatch(500_000)

        // 500.000 − 120.000; bukan 500.000 penuh — dana di dompet payout tak bisa dipakai menagih.
        assertThat(payoutPort.transfers).containsExactly(380_000)
        assertThat(payoutPort.dispatched).hasSize(1)
    }

    @Test
    fun `saldo payout nol - seluruh nominal dipindahkan`() {
        payoutPort.balances[PivotBalanceUsecase.DISBURSEMENT] = 0
        payoutPort.balances[PivotBalanceUsecase.PAYMENT] = 856_600

        dispatch(500_000)

        assertThat(payoutPort.transfers).containsExactly(500_000)
    }

    @Test
    fun `dua dompet digabung pun tak cukup - ditolak sebelum memindahkan apa pun`() {
        payoutPort.balances[PivotBalanceUsecase.DISBURSEMENT] = 100_000
        payoutPort.balances[PivotBalanceUsecase.PAYMENT] = 50_000

        assertThatThrownBy { dispatch(500_000) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("masih kurang Rp 350000")

        assertThat(payoutPort.transfers).isEmpty()
        assertThat(payoutPort.dispatched).isEmpty()
    }

    @Test
    fun `pemindahan saldo gagal - payout ikut batal, tak ada yang dikirim ke Pivot`() {
        payoutPort.balances[PivotBalanceUsecase.DISBURSEMENT] = 0
        payoutPort.balances[PivotBalanceUsecase.PAYMENT] = 856_600
        payoutPort.failTransfer = ConflictException("Pivot menolak POST /v1/withdrawals (400)")

        assertThatThrownBy { dispatch(500_000) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("/v1/withdrawals")

        assertThat(payoutPort.dispatched).isEmpty()
    }

    @Test
    fun `pemindahan saldo dan payout memakai request id berbeda`() {
        // Kalau X-REQUEST-ID-nya sama, Pivot menganggap payout pengulangan pemindahan saldo tadi.
        payoutPort.balances[PivotBalanceUsecase.DISBURSEMENT] = 0
        payoutPort.balances[PivotBalanceUsecase.PAYMENT] = 856_600

        dispatch(500_000)

        assertThat(payoutPort.transferRequestIds.single())
            .isNotEqualTo(payoutPort.payoutRequestIds.single())
    }

    // --- fakes ---

    private fun provisionedAccount() = TenantPivotAccount.defaultFor(tenantId).apply {
        markProvisioned(subId, SubAccountType.NON_KYC, SubAccountStatus.ACTIVE, SubAccountKycStatus.NOT_REQUIRED)
    }

    private class FakePayoutPort : PivotPayoutPort {
        val balances = mutableMapOf(PivotBalanceUsecase.PAYMENT to 0L, PivotBalanceUsecase.DISBURSEMENT to 0L)
        val transfers = mutableListOf<Long>()
        val transferRequestIds = mutableListOf<String>()
        val payoutRequestIds = mutableListOf<String>()
        val dispatched = mutableListOf<PayoutCommand>()
        var failTransfer: RuntimeException? = null

        override fun payout(
            master: PivotMasterContext,
            subMerchantId: String,
            command: PayoutCommand,
            requestId: String,
        ): PayoutDispatch {
            dispatched += command
            payoutRequestIds += requestId
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
        ) {
            failTransfer?.let { throw it }
            transfers += amountMinor
            transferRequestIds += requestId
            balances[PivotBalanceUsecase.PAYMENT] = balances.getValue(PivotBalanceUsecase.PAYMENT) - amountMinor
            balances[PivotBalanceUsecase.DISBURSEMENT] = balances.getValue(PivotBalanceUsecase.DISBURSEMENT) + amountMinor
        }

        override fun balance(
            master: PivotMasterContext,
            subMerchantId: String?,
            usecase: PivotBalanceUsecase,
        ): BalanceSnapshot = BalanceSnapshot(balances.getValue(usecase), "IDR")
    }

    private class FakeSubMerchantPort : PivotSubMerchantPort {
        override fun create(master: PivotMasterContext, request: SubMerchantCreateRequest): SubMerchantResult =
            error("tak dipakai")

        override fun fetch(master: PivotMasterContext, subMerchantUuid: String): SubMerchantResult =
            error("tak dipakai")

        override fun inquiryAccount(
            master: PivotMasterContext,
            subMerchantId: String,
            channelCode: String,
            accountNumber: String,
            accountName: String,
        ): InquiryResult = InquiryResult(inquiryId = "inq-1", status = InquiryStatus.VALID, detail = null)

        override fun assignUser(master: PivotMasterContext, subMerchantId: String, email: String, name: String) = Unit

        override fun resendInvitation(master: PivotMasterContext, subMerchantId: String, email: String) = Unit
    }

    private class FakePayoutRepository : TenantPayoutRepository {
        override fun save(payout: TenantPayout): TenantPayout = payout
        override fun list(): List<TenantPayout> = emptyList()
        override fun findByReference(reference: String): TenantPayout? = null
    }

    private class FakeAccountRepository(private val account: TenantPivotAccount) : TenantPivotAccountRepository {
        override fun find(): TenantPivotAccount = account
        override fun save(account: TenantPivotAccount): TenantPivotAccount = account
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
