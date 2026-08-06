package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.inbound.DispatchPayoutCommand
import com.duluin.ftth.billing.application.port.inbound.ManageTenantPayoutUseCase
import com.duluin.ftth.billing.application.port.inbound.PivotBalanceView
import com.duluin.ftth.billing.application.port.inbound.ReconcilePayoutUseCase
import com.duluin.ftth.billing.application.port.inbound.TenantPayoutView
import com.duluin.ftth.billing.application.port.outbound.PayoutCommand
import com.duluin.ftth.billing.application.port.outbound.PivotPayoutPort
import com.duluin.ftth.billing.application.port.outbound.TenantPayoutRepository
import com.duluin.ftth.billing.application.port.outbound.TenantPivotAccountRepository
import com.duluin.ftth.billing.domain.model.PayoutKind
import com.duluin.ftth.billing.domain.model.PivotMasterContext
import com.duluin.ftth.billing.domain.model.SubAccountType
import com.duluin.ftth.billing.domain.model.TenantPayout
import com.duluin.ftth.billing.domain.model.TenantPivotAccount
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Penyaluran dana tenant di atas akun MASTER Pivot:
 *  - **PAYOUT (NON_KYC)** — operator platform menyalurkan dana dari balance master ke rekening
 *    tenant yang sudah divalidasi (`payoutInquiryId`), lewat `POST /v1/payouts`.
 *  - **WITHDRAWAL (KYC)** — tenant menarik saldo sub-account-nya sendiri, `POST /v1/withdrawals`
 *    on-behalf (`x-submerchant-id`).
 *
 * Nominal EKSPLISIT (tak ada akrual otomatis) — saldo dibaca langsung dari Pivot ([balance]).
 * Tiap perintah dicatat [TenantPayout] (PENDING→PROCESSING) lalu difinalkan callback rekonsiliasi
 * ([reconcile], diverifikasi `X-API-Key` master di webhook). Idempotency `X-REQUEST-ID` diturunkan
 * dari id baris → retry perintah yang sama aman.
 */
@Service
@Transactional(readOnly = true)
class TenantPayoutService(
    private val repository: TenantPayoutRepository,
    private val accounts: TenantPivotAccountRepository,
    private val masterConfig: PivotMasterConfigProvider,
    private val payoutPort: PivotPayoutPort,
    private val auditor: AuditRecorder,
) : ManageTenantPayoutUseCase, ReconcilePayoutUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun history(): List<TenantPayoutView> = repository.list().map { it.toView() }

    override fun balance(): PivotBalanceView {
        val master = requireMaster()
        val account = accounts.find()
        // Dana KYC ada di balance sub-account tenant; NON_KYC di balance master platform.
        val subId = account?.takeIf { it.type == SubAccountType.KYC && it.provisioned }?.subMerchantUuid
        val snapshot = payoutPort.balance(master, subId)
        return PivotBalanceView(
            availableMinor = snapshot.availableMinor,
            pendingMinor = snapshot.pendingMinor,
            currency = snapshot.currency,
            subAccount = subId != null,
        )
    }

    @Transactional
    override fun dispatchPayout(command: DispatchPayoutCommand): TenantPayoutView {
        val master = requireMaster()
        val amount = requireAmount(command.amountMinor)
        val tenantId = TenantContext.tenantId()
        val account = accounts.find() ?: throw ConflictException("Sub-account Pivot tenant belum ada")
        if (account.type != SubAccountType.NON_KYC) {
            throw ConflictException("Payout hanya untuk akun NON_KYC; akun KYC memakai penarikan sendiri")
        }
        if (!account.payoutReady) throw ConflictException("Rekening payout tenant belum divalidasi")

        val payout = newPayout(tenantId, PayoutKind.PAYOUT, amount, account)
        val dispatch = payoutPort.payout(
            master,
            PayoutCommand(
                amountMinor = amount,
                channelCode = account.payoutChannelCode,
                accountNumber = account.payoutAccountNumber,
                inquiryId = account.payoutInquiryId,
                remarks = command.remarks,
            ),
            requestId(payout.id),
        )
        payout.markProcessing(dispatch.reference)
        if (dispatch.settledImmediately) payout.markSuccess()
        val saved = repository.save(payout)
        audit("billing.pivot.payout.dispatched", saved.id, tenantId)
        log.info("Payout NON_KYC tenant {} sebesar {} → ref {}", tenantId, amount, dispatch.reference)
        return saved.toView()
    }

    @Transactional
    override fun withdraw(command: DispatchPayoutCommand): TenantPayoutView {
        val master = requireMaster()
        val amount = requireAmount(command.amountMinor)
        val tenantId = TenantContext.tenantId()
        val account = accounts.find() ?: throw ConflictException("Sub-account Pivot tenant belum ada")
        if (account.type != SubAccountType.KYC || !account.provisioned) {
            throw ConflictException("Penarikan hanya untuk akun KYC yang sudah terprovisi")
        }
        val subId = account.subMerchantUuid
            ?: throw ConflictException("Sub-account tenant belum punya UUID di Pivot")

        val payout = newPayout(tenantId, PayoutKind.WITHDRAWAL, amount, account)
        val dispatch = payoutPort.withdraw(
            master,
            subId,
            PayoutCommand(
                amountMinor = amount,
                channelCode = account.payoutChannelCode,
                accountNumber = account.payoutAccountNumber,
                inquiryId = account.payoutInquiryId,
                remarks = command.remarks,
            ),
            requestId(payout.id),
        )
        payout.markProcessing(dispatch.reference)
        if (dispatch.settledImmediately) payout.markSuccess()
        val saved = repository.save(payout)
        audit("billing.pivot.withdrawal.dispatched", saved.id, tenantId)
        log.info("Withdrawal KYC tenant {} sebesar {} → ref {}", tenantId, amount, dispatch.reference)
        return saved.toView()
    }

    @Transactional
    override fun reconcile(reference: String, success: Boolean, reason: String?) {
        val ref = reference.trim().takeIf { it.isNotEmpty() } ?: return
        val payout = repository.findByReference(ref) ?: run {
            log.info("Callback penyaluran ref {} tak cocok baris mana pun — diabaikan", ref)
            return
        }
        if (success) payout.markSuccess() else payout.markFailed(reason)
        repository.save(payout)
        log.info("Penyaluran ref {} direkonsiliasi → {}", ref, payout.status)
    }

    private fun newPayout(tenantId: UUID, kind: PayoutKind, amount: Long, account: TenantPivotAccount) =
        TenantPayout.create(
            tenantId = tenantId,
            kind = kind,
            amountMinor = amount,
            channelCode = account.payoutChannelCode,
            accountNumber = account.payoutAccountNumber,
            accountName = account.payoutAccountName,
            createdAt = Instant.now(),
        )

    private fun requireAmount(amountMinor: Long): Long =
        amountMinor.takeIf { it > 0 } ?: throw ValidationException("Nominal penyaluran harus lebih dari 0")

    private fun requireMaster(): PivotMasterContext = masterConfig.current()
        ?: throw ConflictException("Pivot belum diaktifkan platform — penyaluran tak bisa dijalankan")

    /** `X-REQUEST-ID` idempotency (alfanumerik 16–36) deterministik dari id baris → retry aman. */
    private fun requestId(id: UUID): String =
        ("req" + id.toString().replace("-", "")).take(36)

    private fun audit(action: String, entityId: UUID, tenantId: UUID) = auditor.record(
        action = action,
        entityType = "TenantPayout",
        entityId = entityId,
        tenantId = tenantId,
    )

    private fun TenantPayout.toView() = TenantPayoutView(
        id = id.toString(),
        kind = kind,
        amountMinor = amountMinor,
        channelCode = channelCode,
        accountNumber = accountNumber,
        accountName = accountName,
        status = status,
        pivotRef = pivotRef,
        failureReason = failureReason,
        createdAt = createdAt,
    )
}
