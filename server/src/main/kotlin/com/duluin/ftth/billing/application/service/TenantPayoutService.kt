package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.inbound.DispatchPayoutCommand
import com.duluin.ftth.billing.application.port.inbound.ManageTenantPayoutUseCase
import com.duluin.ftth.billing.application.port.inbound.PivotBalanceView
import com.duluin.ftth.billing.application.port.inbound.ReconcilePayoutUseCase
import com.duluin.ftth.billing.application.port.inbound.TenantPayoutView
import com.duluin.ftth.billing.application.port.inbound.WithdrawCommand
import com.duluin.ftth.billing.application.port.outbound.PayoutCommand
import com.duluin.ftth.billing.application.port.outbound.PivotBalanceUsecase
import com.duluin.ftth.billing.application.port.outbound.PivotPayoutPort
import com.duluin.ftth.billing.application.port.outbound.PivotSubMerchantPort
import com.duluin.ftth.billing.application.port.outbound.TenantPayoutRepository
import com.duluin.ftth.billing.application.port.outbound.TenantPivotAccountRepository
import com.duluin.ftth.billing.domain.model.PayoutKind
import com.duluin.ftth.billing.domain.model.PivotFeeType
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
 * Nominal EKSPLISIT (tak ada akrual otomatis) — saldo dibaca langsung dari Pivot. Perhatikan dua
 * dompet yang BERBEDA: [balance] menampilkan saldo PAYMENT (hasil tagihan pelanggan, sumber dana
 * withdrawal), sedangkan `POST /v1/payouts` menarik dari saldo DISBURSEMENT. Keduanya dijembatani
 * `ensurePayoutBalance` — memindahkan kekurangannya saja, itupun cuma bila saldo payout memang tak
 * cukup.
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
    private val subMerchant: PivotSubMerchantPort,
    private val auditor: AuditRecorder,
) : ManageTenantPayoutUseCase, ReconcilePayoutUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun history(): List<TenantPayoutView> = repository.list().map { it.toView() }

    override fun balance(): PivotBalanceView {
        val master = requireMaster()
        val account = accounts.find()
        // Saldo dibaca on-behalf sub-account tenant bila sudah terprovisi; else saldo master.
        val subId = account?.takeIf { it.provisioned }?.subMerchantUuid
        val snapshot = payoutPort.balance(master, subId, PivotBalanceUsecase.PAYMENT)
        return PivotBalanceView(
            availableMinor = snapshot.availableMinor,
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
        val subId = account.subMerchantUuid
            ?: throw ConflictException("Sub-account tenant belum terdaftar di Pivot")
        val channelCode = command.channelCode.trim().uppercase().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Channel bank wajib diisi")
        val accountNumber = command.accountNumber.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Nomor rekening wajib diisi")
        val accountName = requireAccountName(command.accountName)

        // Validasi rekening tujuan → inquiryId. Dipakai ulang dari basis data selama rekeningnya tak
        // berubah; inquiry baru hanya ditembak bila datanya beda (lihat resolveInquiryId).
        val inquiryId = resolveInquiryId(master, subId, account, channelCode, accountNumber, accountName)
        // Biaya dipotong dari nominal yang diminta: tenant minta 50.000 → 46.000 masuk rekeningnya,
        // 4.000 dipindahkan ke master. TenantPayout.create menolak bila biayanya menelan nominal.
        val fee = payoutFee(master, amount)
        val payout = TenantPayout.create(
            tenantId = tenantId,
            kind = PayoutKind.PAYOUT,
            amountMinor = amount,
            feeMinor = fee,
            channelCode = channelCode,
            accountNumber = accountNumber,
            accountName = accountName,
            createdAt = Instant.now(),
        )
        // Dompet payout harus menanggung KEDUA leg: nominal bersih ke bank + biaya ke master.
        ensurePayoutBalance(master, subId, amount, payout.id, tenantId)
        val dispatch = payoutPort.payout(
            master,
            subId,
            PayoutCommand(
                amountMinor = payout.netAmountMinor,
                channelCode = channelCode,
                accountNumber = accountNumber,
                accountName = accountName,
                inquiryId = inquiryId,
                referenceId = payout.id.toString(),
                description = command.description,
            ),
            requestId(payout.id),
        )
        payout.markProcessing(dispatch.reference)
        if (dispatch.settledImmediately) payout.markSuccess()
        val saved = repository.save(payout)
        collectPayoutFee(master, subId, payout.id, fee, tenantId)
        audit("billing.pivot.payout.dispatched", saved.id, tenantId)
        log.info(
            "Payout tenant {} sebesar {} (biaya {}, bersih {}) ke {}/{} → ref {}",
            tenantId, amount, fee, payout.netAmountMinor, channelCode, accountNumber, dispatch.reference,
        )
        return saved.toView()
    }

    @Transactional
    override fun withdraw(command: WithdrawCommand): TenantPayoutView {
        val master = requireMaster()
        val amount = requireAmount(command.amountMinor)
        val tenantId = TenantContext.tenantId()
        val account = accounts.find() ?: throw ConflictException("Sub-account Pivot tenant belum ada")
        if (account.type != SubAccountType.KYC || !account.provisioned) {
            throw ConflictException("Penarikan hanya untuk akun KYC yang sudah terprovisi")
        }
        val subId = account.subMerchantUuid
            ?: throw ConflictException("Sub-account tenant belum punya UUID di Pivot")
        if (!account.payoutReady) throw ConflictException("Rekening payout tenant belum divalidasi")

        // Withdrawal mencairkan saldo PAYMENT (hasil tagihan pelanggan) ke rekening terdaftar.
        val snapshot = payoutPort.balance(master, subId, PivotBalanceUsecase.PAYMENT)
        if (snapshot.availableMinor < amount) {
            throw ConflictException(
                "Saldo pembayaran tak cukup — tersedia Rp ${snapshot.availableMinor}, butuh Rp $amount",
            )
        }

        val payout = newPayout(tenantId, PayoutKind.WITHDRAWAL, amount, account)
        val dispatch = payoutPort.withdraw(
            master,
            subId,
            PayoutCommand(
                amountMinor = amount,
                channelCode = account.payoutChannelCode,
                accountNumber = account.payoutAccountNumber,
                accountName = account.payoutAccountName,
                inquiryId = account.payoutInquiryId,
                referenceId = payout.id.toString(),
                description = command.description,
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

    /**
     * `inquiryId` rekening tujuan: dipakai ulang dari basis data bila rekeningnya tak berubah, else
     * inquiry baru ditembak lalu hasilnya DISIMPAN untuk payout berikutnya.
     *
     * `POST /v1/inquiry-account` ditagih Rp 450 per panggilan ke saldo DISBURSEMENT master —
     * termasuk untuk rekening yang itu-itu juga, dan termasuk saat hasilnya ditolak. Menembaknya
     * tiap payout berarti membakar biaya untuk jawaban yang sudah kita punya; Pivot sendiri
     * menganjurkan menyimpan `inquiryId` dan memakainya ulang.
     */
    private fun resolveInquiryId(
        master: PivotMasterContext,
        subId: String,
        account: TenantPivotAccount,
        channelCode: String,
        accountNumber: String,
        accountName: String,
    ): String {
        account.cachedInquiryId(channelCode, accountNumber, accountName)?.let { return it }

        val inquiry = subMerchant
            .inquiryAccount(master, subId, channelCode, accountNumber, accountName)
            .requireValid()
        // Rekening yang baru divalidasi jadi rekening payout tersimpan — payout berikutnya ke tujuan
        // yang sama tak perlu inquiry lagi.
        account.setPayoutAccount(channelCode, accountNumber, accountName, inquiry.inquiryId)
        accounts.save(account)
        log.info(
            "Inquiry rekening payout tenant {} disimpan ({}/{}) — payout berikutnya pakai ulang",
            account.tenantId,
            channelCode,
            accountNumber,
        )
        return inquiry.inquiryId
    }

    /**
     * Biaya payout yang ditagihkan platform ke tenant, menurut setelan master. 0 = platform
     * menanggung sendiri biaya Pivot (perilaku sebelum setelan ini ada).
     *
     * Dipotong dari nominal yang diminta, bukan ditambahkan di atasnya: tenant menyebut angka yang
     * keluar dari dompetnya, dan itu angka yang dia lihat di saldo.
     */
    private fun payoutFee(master: PivotMasterContext, amount: Long): Long = when (master.payoutFeeType) {
        PivotFeeType.FIXED -> master.payoutFeeMinor
        PivotFeeType.PERCENTAGE -> amount * master.payoutFeeMinor / PERCENT_BASIS
    }

    /**
     * Pindahkan biaya payout dari dompet sub ke dompet master (`POST /v1/transfers`). Pivot menagih
     * biaya payout ke dompet DISBURSEMENT master, jadi tanpa langkah ini platform menombok tiap kali
     * tenant menyalurkan dana.
     *
     * Kegagalannya sengaja TIDAK melempar: payoutnya sudah terkirim ke Pivot dan uangnya sudah
     * bergerak — menggagalkan transaksi di sini cuma me-rollback catatan lokal, bukan uangnya, dan
     * malah membuat riwayat tenant tak cocok dengan mutasi bank. Yang tepat adalah menandainya keras
     * supaya piutang ini bisa ditagih menyusul.
     */
    private fun collectPayoutFee(
        master: PivotMasterContext,
        subId: String,
        payoutId: UUID,
        fee: Long,
        tenantId: UUID,
    ) {
        if (fee <= 0) return
        try {
            payoutPort.transferToMaster(
                master = master,
                subMerchantId = subId,
                amountMinor = fee,
                referenceId = "fee-$payoutId",
                remarks = "Biaya payout",
                requestId = requestId(payoutId, "fee"),
            )
            audit("billing.pivot.payout.fee_collected", payoutId, tenantId)
        } catch (e: Exception) {
            audit("billing.pivot.payout.fee_uncollected", payoutId, tenantId)
            log.error(
                "Biaya payout {} tenant {} GAGAL dipindahkan ke master (payout {} tetap jalan): {} — tagih manual",
                fee, tenantId, payoutId, e.message, e,
            )
        }
    }

    /**
     * Pastikan dompet DISBURSEMENT sanggup membayar [amount] — `POST /v1/payouts` HANYA menarik dari
     * situ, sedangkan uang tenant mendarat di dompet PAYMENT.
     *
     * Saldo payout dicek DULU: kalau sudah cukup, tak ada pemindahan sama sekali. Bila kurang, cuma
     * KEKURANGANNYA yang dipindahkan dari saldo pembayaran (`BALANCE_TRANSFER`) — jangan memindahkan
     * lebih dari perlu, dana di dompet payout tak bisa dipakai menagih. Kecuali satu hal: Pivot
     * menolak `BALANCE_TRANSFER` di bawah [MIN_BALANCE_TRANSFER] (`unprocessable_entity`, "The
     * minimum withdrawal is IDR 10.000"), jadi kekurangan yang lebih kecil dibulatkan naik ke batas
     * itu. Kelebihannya mengendap di dompet payout dan terpakai payout berikutnya.
     *
     * Kegagalan pemindahan dibiarkan melempar: payout ikut batal, jadi tak ada payout yang dikirim
     * tanpa saldo.
     */
    private fun ensurePayoutBalance(
        master: PivotMasterContext,
        subId: String,
        amount: Long,
        payoutId: UUID,
        tenantId: UUID,
    ) {
        val payoutBalance = payoutPort.balance(master, subId, PivotBalanceUsecase.DISBURSEMENT).availableMinor
        if (payoutBalance >= amount) return

        val shortfall = amount - payoutBalance
        val moveAmount = maxOf(shortfall, MIN_BALANCE_TRANSFER)
        val paymentBalance = payoutPort.balance(master, subId, PivotBalanceUsecase.PAYMENT).availableMinor
        if (paymentBalance < moveAmount) {
            // Angkanya bisa lebih besar dari kekurangan payout itu sendiri ketika batas minimum
            // Pivot yang mengikat — sebutkan alasannya, kalau tidak selisihnya tampak mengada-ada.
            val floorNote = if (moveAmount > shortfall) {
                " (pemindahan saldo minimal Rp $MIN_BALANCE_TRANSFER)"
            } else {
                ""
            }
            throw ConflictException(
                "Saldo tak cukup untuk payout Rp $amount — saldo payout Rp $payoutBalance, " +
                    "saldo pembayaran Rp $paymentBalance, masih kurang Rp ${moveAmount - paymentBalance}$floorNote.",
            )
        }

        payoutPort.transferToPayoutBalance(
            master = master,
            subMerchantId = subId,
            amountMinor = moveAmount,
            referenceId = "trf-$payoutId",
            description = "Isi saldo payout",
            requestId = requestId(payoutId, "trf"),
        )
        audit("billing.pivot.payout.balance_transferred", payoutId, tenantId)
        log.info(
            "Saldo payout tenant {} kurang {} — dipindahkan {} dari saldo pembayaran (tersedia {})",
            tenantId,
            shortfall,
            moveAmount,
            paymentBalance,
        )
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

    /**
     * `X-REQUEST-ID` idempotency (alfanumerik 16–36) deterministik dari id baris → retry aman.
     * [prefix] memisahkan panggilan berbeda untuk baris yang sama (mis. pemindahan saldo vs payout),
     * kalau dibiarkan sama Pivot menganggapnya pengulangan permintaan yang itu-itu juga.
     */
    private fun requestId(id: UUID, prefix: String = "req"): String =
        (prefix + id.toString().replace("-", "")).take(36)

    private fun audit(action: String, entityId: UUID, tenantId: UUID) = auditor.record(
        action = action,
        entityType = "TenantPayout",
        entityId = entityId,
        tenantId = tenantId,
    )

    private companion object {
        /** Pembagi fee PERCENTAGE — `payoutFeeMinor` diisi sebagai angka persen (mis. 2 = 2%). */
        const val PERCENT_BASIS = 100L

        /**
         * Minimum `BALANCE_TRANSFER` Pivot. Diverifikasi di sandbox: memindahkan Rp 2.000 ditolak
         * `unprocessable_entity` — "The minimum withdrawal is IDR 10.000. Please adjust the amount."
         */
        const val MIN_BALANCE_TRANSFER = 10_000L
    }

    private fun TenantPayout.toView() = TenantPayoutView(
        id = id.toString(),
        kind = kind,
        amountMinor = amountMinor,
        feeMinor = feeMinor,
        netAmountMinor = netAmountMinor,
        channelCode = channelCode,
        accountNumber = accountNumber,
        accountName = accountName,
        status = status,
        pivotRef = pivotRef,
        failureReason = failureReason,
        createdAt = createdAt,
    )
}
