package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.inbound.ManageRefundUseCase
import com.duluin.ftth.billing.application.port.inbound.ReconcileRefundUseCase
import com.duluin.ftth.billing.application.port.inbound.RefundView
import com.duluin.ftth.billing.application.port.inbound.RequestRefundCommand
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.application.port.outbound.PaymentRepository
import com.duluin.ftth.billing.application.port.outbound.RefundRepository
import com.duluin.ftth.billing.application.port.outbound.RefundRequest
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.Refund
import com.duluin.ftth.billing.domain.model.RefundStatus
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Penyedia semu untuk pengembalian yang dikerjakan tangan (transfer manual operator). */
private const val MANUAL = "MANUAL"

/**
 * Pengembalian dana atas tagihan lunas.
 *
 * Penyedia dipilih dari CARA TAGIHAN ITU DIBAYAR (provider pembayaran terakhirnya), bukan dari
 * gateway yang aktif hari ini: uang pulang lewat jalur yang dulu dilaluinya, dan tenant yang baru
 * pindah ke Pivot tak boleh membuat Pivot mengembalikan uang yang dulu masuk lewat transfer bank.
 *
 * Dua jalur:
 *  - **Pivot** — `POST /v1/refunds` on-behalf sub-account, baris naik ke PROCESSING lalu ditutup
 *    callback `REFUND.*` ([reconcile]).
 *  - **MANUAL** — tak ada API; baris berhenti di PENDING sampai operator menyatakan transfernya
 *    sudah dilakukan ([settleManual]).
 *
 * Tagihan hanya berubah saat uangnya BENAR-BENAR kembali ([Invoice.applyRefund] dipanggil pada
 * transisi ke SUCCESS, sekali saja) — permintaan yang masih berjalan ditahan di baris refund.
 */
@Service
@Transactional
class RefundService(
    private val refundRepository: RefundRepository,
    private val invoiceRepository: InvoiceRepository,
    private val paymentRepository: PaymentRepository,
    private val registry: PaymentGatewayRegistry,
    private val gatewayResolver: TenantPaymentGatewayResolver,
    private val auditor: AuditRecorder,
) : ManageRefundUseCase, ReconcileRefundUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    override fun list(invoiceId: UUID?): List<RefundView> {
        val refunds = invoiceId?.let { refundRepository.findByInvoiceId(it) } ?: refundRepository.findAll()
        // Nomor tagihan di-resolve sekali per tagihan — daftar refund biasanya menumpuk di
        // segelintir tagihan yang sama, jadi memanggil per baris hanya menambah query kembar.
        val numbers = refunds.map { it.invoiceId }.distinct()
            .mapNotNull { id -> invoiceRepository.findById(id)?.let { id to it.number } }
            .toMap()
        return refunds.map { it.toView(numbers[it.invoiceId]) }
    }

    /**
     * Ajukan pengembalian dana.
     *
     * Perintah ke penyedia dikirim di dalam transaksi yang sama seperti pencatatan barisnya (pola
     * yang sama dengan [TenantPayoutService.dispatchPayout]): bila penyedia menolak, barisnya ikut
     * batal sehingga tak ada refund tercatat yang sebenarnya tak pernah terkirim.
     */
    override fun request(command: RequestRefundCommand): RefundView {
        val invoice = requireInvoice(command.invoiceId)
        if (invoice.status != InvoiceStatus.PAID && invoice.status != InvoiceStatus.REFUNDED) {
            throw ConflictException(
                "Hanya tagihan lunas yang bisa dikembalikan (status sekarang: ${invoice.status})",
            )
        }
        val amount = resolveAmount(invoice, command.amount)
        val payment = paymentRepository.findByInvoiceId(invoice.id).maxByOrNull { it.paidAt }
        val provider = payment?.provider?.takeIf { it.isNotBlank() } ?: invoice.gatewayProvider ?: MANUAL

        val refund = Refund.request(
            tenantId = invoice.tenantId,
            invoiceId = invoice.id,
            customerId = invoice.customerId,
            paymentId = payment?.id,
            amount = amount,
            reason = command.reason,
            provider = provider,
            note = command.note,
            requestedAt = Instant.now(),
        )

        if (!provider.equals(MANUAL, ignoreCase = true)) {
            dispatch(refund, invoice, payment?.gatewayRef ?: invoice.gatewayRef)
        }
        // Penyedia bisa menyatakan uangnya sudah kembali di respons yang sama (refund saldo, bukan
        // transfer bank) — kalau begitu tagihannya langsung ikut bergerak, tak perlu menunggu callback.
        if (refund.status == RefundStatus.SUCCESS) invoice.applyRefundAndSave(refund.amount)

        val saved = refundRepository.save(refund)
        auditor.record(
            "billing.refund.requested", "Refund", saved.id, saved.tenantId,
            mapOf(
                "invoice" to invoice.number,
                "amount" to amount.toPlainString(),
                "provider" to provider,
                "reason" to saved.reason.name,
            ),
        )
        log.info(
            "Refund {} tagihan {} sebesar {} lewat {} → status {}",
            saved.id, invoice.number, amount, provider, saved.status,
        )
        return saved.toView(invoice.number)
    }

    override fun settleManual(id: UUID, success: Boolean, reason: String?): RefundView {
        val refund = refundRepository.findById(id) ?: throw NotFoundException("Pengembalian $id tidak ditemukan")
        if (!refund.provider.equals(MANUAL, ignoreCase = true)) {
            throw ConflictException(
                "Pengembalian lewat ${refund.provider} ditutup otomatis oleh penyedia, bukan dinyatakan manual",
            )
        }
        if (refund.settled) throw ConflictException("Pengembalian ini sudah selesai (${refund.status})")

        val invoice = requireInvoice(refund.invoiceId)
        finalize(refund, invoice, success, reason)
        val saved = refundRepository.save(refund)
        auditor.record(
            "billing.refund.settled", "Refund", saved.id, saved.tenantId,
            mapOf("invoice" to invoice.number, "status" to saved.status.name),
        )
        return saved.toView(invoice.number)
    }

    /**
     * Rekonsiliasi callback penyedia. Idempotent di dua lapis: baris yang sudah SUCCESS diabaikan
     * (supaya [Invoice.applyRefund] tak dijumlah dua kali oleh callback kembar), dan kegagalan yang
     * datang setelah keberhasilan ditolak di domain ([Refund.markFailed]).
     */
    override fun reconcile(reference: String?, clientReference: String?, success: Boolean, reason: String?) {
        val ref = reference?.trim()?.takeIf { it.isNotEmpty() }
        val refund = ref?.let { refundRepository.findByReference(it) }
            ?: clientReference?.let { raw -> runCatching { UUID.fromString(raw.trim()) }.getOrNull() }
                ?.let { refundRepository.findById(it) }
            ?: run {
                log.info("Callback refund ref {} tak cocok baris mana pun — diabaikan", ref ?: clientReference)
                return
            }
        if (refund.status == RefundStatus.SUCCESS) {
            log.info("Callback refund ref {} diabaikan — baris sudah SUCCESS", ref ?: clientReference)
            return
        }
        // Callback yang mendahului respons HTTP membawa ref yang belum sempat tersimpan — lekatkan
        // sekalian, supaya baris ini tetap bisa ditemukan lewat jalur biasa berikutnya.
        if (ref != null && refund.gatewayRef == null) refund.markProcessing(ref)
        val invoice = requireInvoice(refund.invoiceId)
        finalize(refund, invoice, success, reason)
        refundRepository.save(refund)
        log.info("Refund {} direkonsiliasi → {}", refund.id, refund.status)
    }

    /** Kirim perintah refund ke penyedia & terapkan hasilnya ke [refund]. */
    private fun dispatch(refund: Refund, invoice: Invoice, sessionId: String?) {
        val session = sessionId?.takeIf { it.isNotBlank() } ?: throw ConflictException(
            "Tagihan ${invoice.number} tak punya referensi sesi bayar ${refund.provider} — " +
                "kembalikan dananya lewat transfer manual",
        )
        val gateway = registry.forProvider(refund.provider)
            ?: throw ConflictException("Penyedia '${refund.provider}' tidak tersedia untuk pengembalian dana")
        val result = gateway.refund(
            RefundRequest(
                paymentSessionId = session,
                amount = refund.amount,
                fullAmount = refund.amount.compareTo(invoice.amount) == 0,
                reason = refund.reason.name,
                referenceId = refund.id.toString(),
                description = refund.note,
            ),
            gatewayResolver.resolve(),
        )
        refund.markProcessing(result.reference)
        if (result.settled) refund.markSuccess(Instant.now())
    }

    /** Transisi akhir bersama (callback & penutupan manual): sukses menggerakkan tagihannya. */
    private fun finalize(refund: Refund, invoice: Invoice, success: Boolean, reason: String?) {
        val now = Instant.now()
        if (success) {
            refund.markSuccess(now)
            invoice.applyRefundAndSave(refund.amount)
        } else {
            refund.markFailed(reason, now)
        }
    }

    private fun Invoice.applyRefundAndSave(amount: BigDecimal) {
        applyRefund(amount)
        invoiceRepository.save(this)
    }

    /**
     * Nominal yang boleh diajukan. Selain sisa yang belum pernah kembali ([Invoice.refundableAmount],
     * dijaga juga di domain), permintaan yang MASIH BERJALAN ikut dipotong di sini — kalau tidak,
     * dua permintaan penuh berturut-turut sama-sama lolos dan penyedia mengembalikan dua kali.
     */
    private fun resolveAmount(invoice: Invoice, requested: BigDecimal?): BigDecimal {
        val pending = refundRepository.findByInvoiceId(invoice.id)
            .filter { it.status.open }
            .fold(BigDecimal.ZERO) { acc, r -> acc + r.amount }
        val available = invoice.refundableAmount.subtract(pending)
        if (available.signum() <= 0) {
            throw ConflictException("Tak ada sisa yang bisa dikembalikan dari tagihan ${invoice.number}")
        }
        val amount = requested ?: available
        if (amount.signum() <= 0) throw ValidationException("Nilai pengembalian harus lebih dari 0")
        if (amount > available) {
            throw ConflictException(
                "Nilai pengembalian melebihi sisa yang tersedia (Rp ${available.toPlainString()})",
            )
        }
        return amount
    }

    private fun requireInvoice(id: UUID): Invoice =
        invoiceRepository.findById(id) ?: throw NotFoundException("Tagihan $id tidak ditemukan")
}

internal fun Refund.toView(invoiceNumber: String?) = RefundView(
    id = id,
    invoiceId = invoiceId,
    invoiceNumber = invoiceNumber,
    customerId = customerId,
    amount = amount,
    reason = reason.name,
    status = status.name,
    provider = provider,
    note = note,
    failureReason = failureReason,
    requestedAt = requestedAt,
    completedAt = completedAt,
)
