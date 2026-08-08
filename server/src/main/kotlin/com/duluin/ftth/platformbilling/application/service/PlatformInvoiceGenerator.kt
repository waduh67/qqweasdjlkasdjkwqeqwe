package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.billing.PaymentMethodCatalog
import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.SimulatedChargeStatus
import com.duluin.ftth.billing.application.service.PaymentGatewayRegistry
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionInvoiceRepository
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.duluin.ftth.platformbilling.domain.model.SubscriptionInvoiceStatus
import com.duluin.ftth.platformbilling.domain.model.TenantSubscription
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionInvoice
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Mesin penerbitan tagihan langganan tenant, dipakai bersama trigger manual (service) dan
 * scheduler platform. Untuk tiap langganan yang jatuh tempo: buat tagihan periode berikutnya,
 * charge ke gateway AKTIF (dari [PlatformGatewayResolver] — sumber kebenaran, bukan config lama),
 * lekatkan tautan bayar, lalu majukan `next_invoice_at`.
 *
 * Anti-duplikat: nomor `SUB-<yyyymm>-<tenant8>` unik per periode+tenant; bila tagihan periode ini
 * sudah ada, langganan dilewati (dan `next_invoice_at` tetap dimajukan agar tak tersangkut).
 */
@Component
class PlatformInvoiceGenerator(
    private val subscriptionRepository: TenantSubscriptionRepository,
    private val invoiceRepository: TenantSubscriptionInvoiceRepository,
    private val resolver: PlatformGatewayResolver,
    private val gatewayRegistry: PaymentGatewayRegistry,
    private val tenantApi: TenantApi,
    private val iamApi: IamApi,
    private val auditor: AuditRecorder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Terbitkan tagihan langganan untuk [subscription] bila jatuh temponya sudah tiba
     * ([TenantSubscription.nextInvoiceAt] <= [today]) atau [force] (trigger manual super-admin /
     * perpanjangan mandiri tenant). [months] > 1 = bayar di muka beberapa bulan sekaligus: nilai
     * tagihan `monthly_fee × months`, dan periode membentang sepanjang itu. Pada perpanjangan [force]
     * yang masih aktif, periode dimulai dari ujung masa aktif berjalan (menyambung, bukan menimpa).
     * Mengembalikan tagihan yang dibuat, atau null bila dilewati.
     */
    fun issueFor(
        subscription: TenantSubscription,
        today: LocalDate,
        force: Boolean = false,
        months: Int = 1,
    ): TenantSubscriptionInvoice? {
        if (subscription.isCancelled) return null
        val due = subscription.nextInvoiceAt
        if (!force && (due == null || due.isAfter(today))) return null

        val span = months.coerceAtLeast(1).toLong()
        val setting = resolver.setting()
        // Perpanjangan (force) saat masih aktif menyambung dari ujung masa aktif → nomor periode
        // beda dari tagihan bulan berjalan (hindari tabrakan) & terbaca "prabayar ke depan".
        val activeUntil = subscription.currentPeriodEnd
        val periodStart = when {
            force && activeUntil != null && !activeUntil.isBefore(today) -> activeUntil
            else -> due ?: today
        }
        val periodEnd = periodStart.plusMonths(span).minusDays(1)
        val baseNumber = "SUB-${periodStart.format(YEAR_MONTH)}-${tenantShort(subscription.tenantId)}"

        // Majukan HANYA jadwal tagihan berikutnya (melewati seluruh bulan prabayar) agar langganan
        // tak tersangkut & scheduler tak menagih dobel; masa aktif (`currentPeriodEnd`) TIDAK
        // diperpanjang di sini — itu terjadi saat tagihan LUNAS ([TenantSubscription.extendOnPayment]).
        subscription.scheduleNextInvoice(periodStart.plusMonths(span))
        subscriptionRepository.save(subscription)

        // Resolusi tabrakan nomor per status tagihan periode ini (nomor bulanan `SUB-<yyyymm>` unik):
        //  - belum lunas  → idempoten, pakai ulang tagihan itu (klik-ganda renew / scheduler re-run);
        //  - sudah LUNAS  → jangan terbit ganda (skip);
        //  - DIBATALKAN   → tagihan lama VOID tak boleh mengunci periode → terbit ulang nomor unik.
        val existing = invoiceRepository.findByNumber(baseNumber)
        val number = when (existing?.status) {
            null -> baseNumber
            SubscriptionInvoiceStatus.ISSUED, SubscriptionInvoiceStatus.OVERDUE -> return existing
            SubscriptionInvoiceStatus.PAID -> {
                log.info("Tagihan langganan {} sudah lunas — dilewati", baseNumber)
                return null
            }
            SubscriptionInvoiceStatus.VOID -> nextReissueNumber(baseNumber)
        }

        val dueDate = today.plusDays(setting.defaultDueDays.toLong())
        var invoice = TenantSubscriptionInvoice.create(
            tenantId = subscription.tenantId,
            subscriptionId = subscription.id,
            number = number,
            periodStart = periodStart,
            periodEnd = periodEnd,
            amount = subscription.monthlyFee.multiply(BigDecimal.valueOf(span)),
            dueDate = dueDate,
        )
        invoice = invoiceRepository.save(invoice)

        // Charge TIDAK dibuat saat terbit: instrumen bayar (VA/QRIS) dipilih tenant nanti lewat
        // "Bayar"/"Perpanjang" → [chargeWithMethod]. Penerbitan cukup menyimpan tagihannya.

        auditor.record(
            action = "platform.subscription.invoice.issued",
            entityType = "TenantSubscriptionInvoice",
            entityId = invoice.id,
            tenantId = tenantApi.platformTenantId(),
            detail = mapOf("number" to number, "tenantId" to subscription.tenantId.toString()),
        )
        return invoice
    }

    /**
     * Buat charge in-app (mode API Pivot) untuk [invoice] dengan instrumen [method]
     * (`VIRTUAL_ACCOUNT`/`QR`) + [channel] bank (wajib untuk VA), lekatkan instruksi bayar (nomor VA /
     * string QRIS) lewat [TenantSubscriptionInvoice.attachInstruction], simpan, lalu kembalikan.
     * Dipakai jalur "Bayar"/"Perpanjang" tenant saat tenant memilih metode. Mengganti metode
     * (VA↔QRIS) membuat charge baru yang menimpa instruksi lama; `X-REQUEST-ID` Pivot beda per
     * metode → tak tabrakan sesi. Kegagalan charge **dilempar** (aksi dipicu pengguna).
     */
    fun chargeWithMethod(
        invoice: TenantSubscriptionInvoice,
        subscription: TenantSubscription,
        method: String,
        channel: String?,
    ): TenantSubscriptionInvoice {
        PaymentMethodCatalog.validate(method, channel)
        val (normMethod, normChannel) = PaymentMethodCatalog.normalize(method, channel)
        val ctx = resolver.resolveActive()
        val gateway = gatewayRegistry.forProvider(ctx.provider)
            ?: error("Adapter gateway '${ctx.provider}' tidak tersedia")
        val tenant = tenantApi.requireById(subscription.tenantId)
        // Pivot mewajibkan email pelanggan → pakai email admin tenant (login onboarding pertama);
        // resolusi non-RLS agar tetap jalan dari scheduler platform tanpa tenant context.
        val result = gateway.createCharge(
            ChargeRequest(
                invoiceNumber = invoice.number,
                amount = invoice.amount,
                customerName = tenant.name,
                customerEmail = iamApi.primaryEmailForTenant(subscription.tenantId),
                description = "Langganan aplikasi ${tenant.name} — ${invoice.periodStart.format(YEAR_MONTH)}",
                dueDate = invoice.dueDate,
                method = normMethod,
                vaChannel = normChannel,
            ),
            ctx,
        )
        invoice.attachInstruction(
            provider = result.provider,
            gatewayRef = result.gatewayRef,
            method = result.method ?: normMethod,
            vaChannel = result.virtualAccount?.channel ?: normChannel,
            vaNumber = result.virtualAccount?.number,
            vaName = result.virtualAccount?.name,
            vaExpiresAt = result.virtualAccount?.expiresAt,
            qrContent = result.qr?.content,
            qrUrl = result.qr?.url,
            qrExpiresAt = result.qr?.expiresAt,
        )
        return invoiceRepository.save(invoice)
    }

    /**
     * Paksa sesi bayar [invoice] menjadi [status] lewat simulasi sandbox penyedia — alat uji agar
     * alur "bayar → webhook → lunas → masa aktif bertambah" bisa dicoba tanpa transaksi sungguhan.
     * Memakai id sesi bayar yang sudah tersimpan ([TenantSubscriptionInvoice.gatewayRef]).
     *
     * TIDAK menyentuh tagihan: pelunasan datang lewat callback penyedia ([PlatformPaymentService]).
     */
    fun simulatePayment(invoice: TenantSubscriptionInvoice, status: SimulatedChargeStatus) {
        val sessionId = invoice.gatewayRef?.takeIf { it.isNotBlank() }
            ?: throw ConflictException("Tagihan belum punya sesi bayar — tekan Bayar dulu untuk membuat charge")
        val ctx = resolver.resolveActive()
        if (!ctx.provider.equals(invoice.gatewayProvider, ignoreCase = true)) {
            throw ConflictException(
                "Sesi bayar tagihan ini dibuat lewat '${invoice.gatewayProvider}', " +
                    "sedangkan gateway aktif '${ctx.provider}' — simulasi tidak bisa dijalankan",
            )
        }
        val gateway = gatewayRegistry.forProvider(ctx.provider)
            ?: error("Adapter gateway '${ctx.provider}' tidak tersedia")
        gateway.simulateCharge(sessionId, status, ctx)
        log.info("Simulasi pembayaran langganan {} dikirim dengan status {}", invoice.number, status)
    }

    /** Nomor terbit-ulang unik untuk periode yang tagihan lamanya VOID: `<base>-R2`, `-R3`, … */
    private fun nextReissueNumber(base: String): String {
        var suffix = 2
        while (invoiceRepository.findByNumber("$base-R$suffix") != null) suffix++
        return "$base-R$suffix"
    }

    private companion object {
        val YEAR_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
        fun tenantShort(tenantId: java.util.UUID): String =
            tenantId.toString().replace("-", "").take(8)
    }
}
