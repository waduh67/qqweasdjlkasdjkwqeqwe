package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.PaymentMethodCatalog
import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.application.port.outbound.SimulatedChargeStatus
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.Proration
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.customer.BillableSubscription
import com.duluin.ftth.customer.CustomerApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Kontrak sempit "buat charge in-app untuk sebuah tagihan", dipisah agar konsumen yang hanya
 * butuh mem-*charge* (mis. [BillingApiService] untuk portal) tak perlu menyeret seluruh
 * kolaborator [InvoiceGenerator]. Diimplementasikan oleh [InvoiceGenerator].
 */
interface InvoiceChargePort {
    /** Lihat [InvoiceGenerator.chargeWithMethod]. Mutasi [invoice] di tempat; pemanggil menyimpan. */
    fun chargeWithMethod(invoice: Invoice, method: String, channel: String?)
}

/**
 * Mesin penerbitan tagihan periode berjalan, dipakai bersama oleh trigger manual
 * (service) dan scheduler. Langganan yang ditagih ditarik lewat [CustomerApi] —
 * module billing tak menyentuh agregat langganan; ia bertanya lewat kontrak lintas
 * module dan menerima hanya langganan tenant aktif (ter-scope RLS).
 *
 * Anti-duplikat dijaga dua lapis: [InvoiceRepository.existsForPeriod] menyaring
 * langganan yang sudah punya tagihan periode ini, dan unique (tenant, subscription,
 * period_start) di DB menjadi pengaman terakhir.
 */
@Component
class InvoiceGenerator(
    private val invoiceRepository: InvoiceRepository,
    private val customerApi: CustomerApi,
    private val gatewayRegistry: PaymentGatewayRegistry,
    private val gatewayResolver: TenantPaymentGatewayResolver,
    private val taxResolver: BillingTaxSettingsResolver,
    private val auditor: AuditRecorder,
    private val properties: BillingProperties,
) : InvoiceChargePort {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Terbitkan tagihan periode bulan [today] untuk [tenantId]. Nomor urut bersifat
     * global-per-periode (lanjut dari jumlah tagihan periode yang sudah ada) agar
     * penerbitan susulan tidak bentrok nomor. Mengembalikan jumlah tagihan yang dibuat.
     */
    fun generateFor(tenantId: UUID, today: LocalDate): Int {
        val periodStart = today.withDayOfMonth(1)
        val periodEnd = today.withDayOfMonth(today.lengthOfMonth())
        val yyyyMM = periodStart.format(YEAR_MONTH)

        // Gating tanggal penagihan per-langganan: paket boleh menimpa billingDayOfMonth
        // (null = ikut global). Langganan yang tanggal tagihnya belum tiba dilewati
        // ronde ini — akan terbit pada ronde berikutnya setelah tanggalnya tercapai.
        val billable = customerApi.findBillableSubscriptions().filter { sub ->
            sub.monthlyFee.signum() > 0 &&
                today.dayOfMonth >= (sub.billingDayOfMonth ?: properties.billingDayOfMonth) &&
                !invoiceRepository.existsForPeriod(sub.subscriptionId, periodStart)
        }
        if (billable.isEmpty()) return 0

        // Setelan pajak di-resolve SEKALI per ronde: PPN menjadi tarif
        // yang sama untuk semua tagihan periode ini. Nonaktif → null (tagihan tanpa PPN).
        val taxRate = taxResolver.resolve().effectivePpnRate()
        val base = invoiceRepository.countForPeriod(periodStart)

        var issued = 0
        billable.forEachIndexed { index, sub ->
            val seq = base + index + 1
            val number = "${properties.numberPrefix}-$yyyyMM-${seq.toString().padStart(SEQ_WIDTH, '0')}"
            // Dasar (DPP) dihitung sekali dari prorata/tarif penuh; tagihan menambahkan PPN di
            // atasnya (bila aktif). Charge gateway memakai TOTAL tagihan (invoice.amount) agar
            // pelanggan membayar persis nilai tagihannya — dasar + PPN konsisten satu sumber.
            val proration = prorationFor(sub, periodStart, periodEnd)
            val baseAmount = proration?.amount ?: sub.monthlyFee
            val invoice = Invoice.create(
                tenantId = tenantId,
                customerId = sub.customerId,
                subscriptionId = sub.subscriptionId,
                number = number,
                periodStart = periodStart,
                periodEnd = periodEnd,
                baseAmount = baseAmount,
                dueDate = today.plusDays(properties.dueDays),
                taxRate = taxRate,
                prorated = proration != null,
                proratedDays = proration?.days,
            )
            // Charge TIDAK dibuat saat terbit: instrumen bayar (VA/QRIS) dipilih pelanggan nanti
            // lewat "Bayar" → [chargeWithMethod]. Penerbitan cukup menyimpan tagihannya.
            val saved = invoiceRepository.save(invoice)
            auditor.record(
                "billing.invoice.issued", "Invoice", saved.id, saved.tenantId,
                mapOf("number" to saved.number, "amount" to saved.amount),
            )
            issued++
        }
        return issued
    }

    /**
     * Buat charge in-app (mode API Pivot) untuk [invoice] dengan instrumen [method]
     * (`VIRTUAL_ACCOUNT`/`QR`) + [channel] bank (wajib untuk VA), lalu lekatkan instruksi bayar
     * (nomor VA / string QRIS) ke tagihan lewat [Invoice.attachInstruction]. Dipakai saat pelanggan
     * menekan "Bayar" dan memilih metode. Mengganti metode (VA↔QRIS) membuat charge baru yang
     * menimpa instruksi lama. Pemanggil wajib menyimpan tagihan.
     *
     * Charge memakai penyedia gateway yang **aktif sekarang**. Penyedia MANUAL ditolak (tak ada
     * instruksi in-app — pelanggan bayar transfer/tunai lewat panel manual). Kegagalan charge
     * **dilempar** ke pemanggil (aksi dipicu pengguna) agar kesalahan setelan terlihat.
     */
    override fun chargeWithMethod(invoice: Invoice, method: String, channel: String?) {
        PaymentMethodCatalog.validate(method, channel)
        val (normMethod, normChannel) = PaymentMethodCatalog.normalize(method, channel)
        val ctx = gatewayResolver.resolve()
        if (ctx.provider.equals("MANUAL", ignoreCase = true)) {
            throw ConflictException("Gateway aktif MANUAL tidak mendukung pembayaran in-app")
        }
        val gateway = gatewayRegistry.forProvider(ctx.provider)
            ?: error("Adapter gateway '${ctx.provider}' tidak tersedia")
        val customer = customerApi.findCustomer(invoice.customerId)
        val charge = gateway.createCharge(
            ChargeRequest(
                invoiceNumber = invoice.number,
                amount = invoice.amount,
                customerName = customer?.name ?: invoice.number,
                customerEmail = customer?.email,
                description = "Tagihan ${invoice.number}",
                dueDate = invoice.dueDate,
                method = normMethod,
                vaChannel = normChannel,
            ),
            ctx,
        )
        invoice.attachInstruction(
            provider = charge.provider,
            gatewayRef = charge.gatewayRef,
            method = charge.method ?: normMethod,
            vaChannel = charge.virtualAccount?.channel ?: normChannel,
            vaNumber = charge.virtualAccount?.number,
            vaName = charge.virtualAccount?.name,
            vaExpiresAt = charge.virtualAccount?.expiresAt,
            qrContent = charge.qr?.content,
            qrUrl = charge.qr?.url,
            qrExpiresAt = charge.qr?.expiresAt,
        )
    }

    /**
     * Paksa sesi bayar tagihan [invoice] menjadi [status] lewat simulasi sandbox penyedia — alat uji
     * agar alur "bayar → webhook → lunas" bisa dicoba tanpa transaksi bank/e-wallet sungguhan.
     * Memakai id sesi bayar yang sudah tersimpan ([Invoice.gatewayRef], hasil charge terakhir).
     *
     * TIDAK menyentuh tagihan: penyedia mengirim callback seperti pembayaran nyata, dan pelunasan
     * terjadi di jalur webhook. Ditolak bila tagihan belum pernah di-charge atau penyedia yang
     * melekat bukan penyedia aktif sekarang (id sesi milik akun lain).
     */
    fun simulatePayment(invoice: Invoice, status: SimulatedChargeStatus) {
        val sessionId = invoice.gatewayRef?.takeIf { it.isNotBlank() }
            ?: throw ConflictException("Tagihan belum punya sesi bayar — tekan Bayar dulu untuk membuat charge")
        val ctx = gatewayResolver.resolve()
        if (!ctx.provider.equals(invoice.gatewayProvider, ignoreCase = true)) {
            throw ConflictException(
                "Sesi bayar tagihan ini dibuat lewat '${invoice.gatewayProvider}', " +
                    "sedangkan gateway aktif '${ctx.provider}' — simulasi tidak bisa dijalankan",
            )
        }
        val gateway = gatewayRegistry.forProvider(ctx.provider)
            ?: error("Adapter gateway '${ctx.provider}' tidak tersedia")
        gateway.simulateCharge(sessionId, status, ctx)
        log.info("Simulasi pembayaran tagihan {} dikirim dengan status {}", invoice.number, status)
    }

    /**
     * Prorata untuk [sub] pada periode berjalan, atau null (tagih penuh). Aktif bila
     * flag paket [BillableSubscription.prorateOnActivation] menyala (null = ikut global
     * [BillingProperties.prorateOnActivation]) dan langganan punya `activatedAt` di dalam
     * periode. Tanggal aktivasi diambil di zona sistem — selaras dengan `today` penerbit.
     */
    private fun prorationFor(sub: BillableSubscription, periodStart: LocalDate, periodEnd: LocalDate): Proration? {
        val enabled = sub.prorateOnActivation ?: properties.prorateOnActivation
        if (!enabled) return null
        val activatedAt = sub.activatedAt ?: return null
        val activationDate = LocalDate.ofInstant(activatedAt, ZoneId.systemDefault())
        return Invoice.prorate(sub.monthlyFee, activationDate, periodStart, periodEnd)
    }

    private companion object {
        val YEAR_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
        const val SEQ_WIDTH = 4
    }
}
