package com.duluin.ftth.platformbilling.application.port.inbound

import com.duluin.ftth.platformbilling.domain.model.SubscriptionInvoiceStatus
import com.duluin.ftth.platformbilling.domain.model.SubscriptionStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Sisi super-admin: kelola langganan sebuah tenant ke aplikasi (biaya bulanan flat, status) +
 * tagihan & pembayarannya. Semua operasi lintas-tenant → dijaga izin `platform.subscription.*`.
 */
interface ManageTenantSubscriptionUseCase {
    /** Ringkasan langganan + tagihan sebuah tenant; null bila tenant belum berlangganan. */
    fun get(tenantId: UUID): TenantSubscriptionDetailView?

    /** Buat/ubah biaya bulanan & default per-tenant. Membuat langganan bila belum ada. */
    fun configure(tenantId: UUID, command: ConfigureSubscriptionCommand): TenantSubscriptionDetailView

    /** Terbitkan tagihan periode berjalan sekarang (trigger manual). */
    fun generateInvoice(tenantId: UUID): SubscriptionInvoiceView

    /** Batalkan sebuah tagihan (mis. salah terbit). */
    fun voidInvoice(invoiceId: UUID): SubscriptionInvoiceView

    /** Catat pembayaran manual (mis. transfer di luar gateway) atas tagihan. */
    fun recordManualPayment(invoiceId: UUID, command: ManualPaymentCommand): SubscriptionInvoiceView

    /** Hentikan langganan tenant (berhenti ditagih). */
    fun cancel(tenantId: UUID): TenantSubscriptionDetailView
}

data class ConfigureSubscriptionCommand(
    val monthlyFee: BigDecimal,
    val billingDay: Int?,
    val graceDays: Int?,
)

data class ManualPaymentCommand(
    val amount: BigDecimal?,
    val note: String?,
)

data class TenantSubscriptionDetailView(
    val tenantId: UUID,
    val monthlyFee: BigDecimal,
    val status: SubscriptionStatus,
    val billingDay: Int?,
    val graceDays: Int?,
    val currentPeriodStart: LocalDate?,
    val currentPeriodEnd: LocalDate?,
    val nextInvoiceAt: LocalDate?,
    val activatedAt: Instant?,
    val invoices: List<SubscriptionInvoiceView>,
)

data class SubscriptionInvoiceView(
    val id: UUID,
    val tenantId: UUID,
    val number: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val amount: BigDecimal,
    val status: SubscriptionInvoiceStatus,
    val issuedAt: Instant,
    val dueDate: LocalDate,
    val paidAt: Instant?,
    val gatewayProvider: String?,
    val payUrl: String?,
    /** Instrumen bayar in-app terpilih (VIRTUAL_ACCOUNT/QR) & instruksinya; null bila belum pilih. */
    val payMethod: String? = null,
    val vaChannel: String? = null,
    val vaNumber: String? = null,
    val vaName: String? = null,
    val vaExpiresAt: Instant? = null,
    /** String QRIS mentah (dirender jadi kode QR di klien). */
    val qrContent: String? = null,
    val qrUrl: String? = null,
    val qrExpiresAt: Instant? = null,
    /**
     * Tagihan ini bisa dipaksa lunas/kedaluwarsa lewat simulasi sandbox penyedia (alat uji): Pivot
     * dalam mode sandbox, sesi bayar sudah dibuat, dan tagihan masih tertunggak. UI memakainya
     * untuk memunculkan aksi "Simulasi bayar" — di produksi selalu `false`.
     */
    val simulatable: Boolean = false,
)
