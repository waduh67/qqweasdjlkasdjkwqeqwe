package com.duluin.ftth.portal.application.port.inbound

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Baca-saja self-service pelanggan portal. SEMUA metode ter-scope ke SATU pelanggan
 * ([customerId]) — controller mengisinya dari principal portal yang login, tak pernah dari
 * input klien, sehingga pelanggan hanya bisa membaca datanya sendiri.
 *
 * Merangkai kontrak publik modul lain (customer, catalog, billing, bng, cpe) — persis pola
 * `ReportService`/`Subscriber360Service`. Portal tak menyentuh tabel modul lain.
 */
interface PortalSelfServiceUseCase {

    /** Profil pelanggan + langganan beserta detail paket (Profil & paket). */
    fun profile(customerId: UUID): PortalAccountView

    /** Ringkasan rekening + daftar tagihan (tautan bayar) + riwayat pembayaran. */
    fun billing(customerId: UUID): PortalBillingView

    /** Metode bayar in-app yang tersedia (QRIS + Virtual Account). */
    fun paymentMethods(customerId: UUID): List<PortalPaymentMethodView>

    /**
     * Buat charge in-app (VA/QRIS) untuk satu tagihan pelanggan berjalan dengan instrumen [method]
     * (`VIRTUAL_ACCOUNT`/`QR`) + [channel] bank (wajib untuk VA), lalu kembalikan tagihan berisi
     * instruksi bayar. Dibatasi ke [customerId] (principal portal) — tak bisa membayar tagihan orang lain.
     */
    fun payInvoice(customerId: UUID, invoiceId: UUID, method: String, channel: String?): PortalInvoiceView

    /** Status koneksi: sesi PPPoE terkini + perangkat CPE. */
    fun connection(customerId: UUID): PortalConnectionView

    /**
     * Satu tagihan LENGKAP untuk lembar cetak: penerbit, penerima, rincian pajak, dan
     * pembayaran yang sudah masuk. Dibatasi ke [customerId]; NotFound bila bukan miliknya.
     */
    fun invoiceForPrint(customerId: UUID, invoiceId: UUID): PortalInvoicePrintView

    /**
     * Paket yang bisa dipilih pelanggan saat mengajukan ganti paket, dengan penanda paket
     * yang sedang dipakainya sekarang.
     */
    fun planOptions(customerId: UUID): List<PortalPlanOptionView>

    /**
     * Pelanggan mengajukan pindah paket. Ajuan tak langsung mengubah langganan — ia menjadi
     * TIKET berkategori `GANTI_PAKET` agar operator memutuskan (harga, prorata, kunjungan
     * teknisi bila perlu ganti perangkat). Pelanggan menerima nomor tiket untuk diikuti.
     */
    fun requestPlanChange(customerId: UUID, command: PortalPlanChangeCommand): PortalPlanChangeReceiptView
}

data class PortalAccountView(
    val customerId: UUID,
    val code: String,
    val name: String,
    val phone: String?,
    val status: String,
    /** Langganannya — tunggal; null hanya untuk pelanggan warisan yang belum berpaket. */
    val subscription: PortalSubscriptionView?,
)

data class PortalSubscriptionView(
    val subscriptionId: UUID,
    val packageName: String,
    val bandwidthMbps: Int,
    val status: String,
    /** Harga paket dari katalog (indikatif); `null` bila paket telah dinonaktifkan/terhapus. */
    val monthlyFee: BigDecimal?,
    val downMbps: Int?,
    val upMbps: Int?,
    val fupEnabled: Boolean,
    val fupQuotaMb: Long?,
)

data class PortalBillingView(
    val outstandingAmount: BigDecimal,
    val outstandingCount: Int,
    val unpaidCount: Int,
    val oldestDueDate: LocalDate?,
    val lastPaidAt: Instant?,
    val invoices: List<PortalInvoiceView>,
    val payments: List<PortalPaymentView>,
)

data class PortalInvoiceView(
    val id: UUID,
    val number: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val amount: BigDecimal,
    val status: String,
    val issuedAt: Instant,
    val dueDate: LocalDate,
    val paidAt: Instant?,
    /** Bisa dibayar online sekarang: masih terbuka (ISSUED/OVERDUE). */
    val payable: Boolean,
    /** Tautan bayar hosted gateway (charge REDIRECT lama); `null` di alur in-app. */
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
)

/** Metode bayar yang ditawarkan ke pelanggan portal; [channels] kosong bila tak perlu pilih bank (QRIS). */
data class PortalPaymentMethodView(
    val type: String,
    val label: String,
    val channels: List<PortalVaChannelView>,
)

/** Satu bank Virtual Account untuk portal: [code] = channel, [label] nama tampil. */
data class PortalVaChannelView(
    val code: String,
    val label: String,
)

data class PortalPaymentView(
    val id: UUID,
    val invoiceId: UUID,
    /** Nomor tagihan yang dilunasi; null bila tagihannya sudah tak ada di daftar pelanggan. */
    val invoiceNumber: String?,
    val amount: BigDecimal,
    val provider: String,
    val paidAt: Instant,
    val note: String?,
)

/**
 * Lembar tagihan siap cetak. Sengaja MEMBAWA SENDIRI identitas penerbit & penerima alih-alih
 * mengandalkan klien menempelkannya: yang dicetak lalu disimpan/dilampirkan pelanggan harus
 * lengkap berdiri sendiri, dan nilainya tak boleh berubah bila tampilan portal berubah.
 *
 * [issuerName] = nama ISP (tenant) apa adanya; portal tak menyimpan alamat/NPWP tenant, jadi
 * lembar ini adalah bukti tagihan, bukan faktur pajak.
 */
data class PortalInvoicePrintView(
    val issuerName: String,
    val customerName: String,
    val customerCode: String,
    val packageName: String?,
    val invoice: PortalInvoiceView,
    /** DPP (sebelum PPN) — sama dengan `invoice.amount` bila tenant tak memungut PPN. */
    val baseAmount: BigDecimal,
    val taxAmount: BigDecimal,
    val taxRate: BigDecimal?,
    val prorated: Boolean,
    val proratedDays: Int?,
    val payments: List<PortalPaymentView>,
)

/**
 * Satu paket yang bisa dipilih di ajuan ganti paket. [current] menandai paket yang sedang
 * dipakai langganan yang sedang dilihat — supaya pelanggan tak mengajukan pindah ke paket
 * yang sudah dipakainya.
 */
data class PortalPlanOptionView(
    val planId: UUID,
    val name: String,
    val monthlyFee: BigDecimal,
    val bandwidthMbps: Int,
    val downMbps: Int?,
    val upMbps: Int?,
    val fupEnabled: Boolean,
    val fupQuotaMb: Long?,
    val current: Boolean,
)

/** [note] alasan/catatan pelanggan (opsional) — ikut jadi badan tiket. */
data class PortalPlanChangeCommand(
    val subscriptionId: UUID,
    val targetPlanId: UUID,
    val note: String?,
)

/** Bukti ajuan: nomor tiket yang bisa diikuti pelanggan di menu Bantuan. */
data class PortalPlanChangeReceiptView(
    val ticketId: UUID,
    val ticketCode: String,
    val subject: String,
    val status: String,
)

data class PortalConnectionView(
    val session: PortalSessionView?,
    val devices: List<PortalDeviceView>,
)

/** Sesi PPPoE terkini pelanggan — tanpa rahasia (password/secret tak pernah keluar). */
data class PortalSessionView(
    val username: String,
    val accessStatus: String,
    val planName: String?,
    val online: Boolean,
    val framedIp: String?,
    val nasName: String?,
    val uptimeSeconds: Long?,
    val startedAt: Instant?,
    val lastSeenAt: Instant?,
)

data class PortalDeviceView(
    val deviceId: UUID,
    val serialNumber: String,
    val manufacturer: String?,
    val model: String?,
    val softwareVersion: String?,
    val ipAddress: String?,
    val online: Boolean,
    val lastInformAt: Instant?,
)
