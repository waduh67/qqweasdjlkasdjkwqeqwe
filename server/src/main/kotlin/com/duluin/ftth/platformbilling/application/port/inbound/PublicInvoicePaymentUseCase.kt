package com.duluin.ftth.platformbilling.application.port.inbound

import com.duluin.ftth.billing.PaymentMethodOption
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Halaman bayar publik: satu tagihan dibuka & dibayar lewat tautan `/bayar/<slug>/<uuid>` TANPA
 * login. Kapabilitasnya adalah UUID tagihan itu sendiri — siapa pun yang memegang tautannya boleh
 * membayar (itulah gunanya: tautan dikirim ke pelanggan lewat WhatsApp).
 *
 * Menyatukan DUA jenis tagihan di balik satu bentuk: tagihan pelanggan (tenant → pelanggan, module
 * `billing`, ter-RLS) dan tagihan langganan SaaS (platform → tenant, module `platformbilling`,
 * level platform). Pemanggil tak perlu tahu yang mana.
 */
interface PublicInvoicePaymentUseCase {
    /**
     * Tagihan pada tautan publik. Melempar NotFound dengan KALIMAT YANG SAMA untuk semua sebab
     * (slug asing, UUID asing, tagihan tenant lain) — jangan bocorkan mana yang salah ke pemegang
     * tautan yang menebak-nebak. Juga dipakai klien untuk polling status pelunasan.
     */
    fun find(tenantSlug: String, invoiceId: UUID): PublicInvoiceView

    /**
     * Pilih instrumen bayar ([method] `VIRTUAL_ACCOUNT`/`QR` + [channel] bank untuk VA) lalu
     * kembalikan tagihan berisi instruksinya. Instruksi hidup yang cocok DIPAKAI ULANG — memuat
     * ulang tautan tak boleh menghambur sesi bayar baru di penyedia.
     */
    fun pay(tenantSlug: String, invoiceId: UUID, method: String, channel: String?): PublicInvoiceView

    /** Metode bayar in-app yang ditawarkan (statis, sama untuk kedua jenis tagihan). */
    fun paymentMethods(): List<PaymentMethodOption>

    /**
     * Gambar QRIS statis tenant (gateway MANUAL) untuk tagihan pada tautan ini; null bila tak
     * dipasang. Tautan tetap divalidasi dulu agar gambar tak bisa dipanen hanya dari slug.
     */
    fun manualQrisImage(tenantSlug: String, invoiceId: UUID): ByteArrayContent?
}

/** Isi biner + tipe kontennya, untuk disajikan controller apa adanya. */
data class ByteArrayContent(val contentType: String, val bytes: ByteArray)

/**
 * Tagihan sebagaimana ditampilkan halaman bayar publik. Subset PALING SEMPIT yang cukup untuk
 * membayar: TIDAK ada `gatewayRef`, id sesi bayar, penanda simulasi, apalagi data pelanggan lain —
 * pemegang tautan belum tentu pemilik tagihan.
 *
 * [payableOnline] = tagihan masih terbuka DAN gateway aktif mendukung bayar in-app → panel VA/QRIS
 * layak dirender. Bila false karena gateway tenant MANUAL, [manual] berisi instruksi transfernya.
 */
data class PublicInvoiceView(
    val id: UUID,
    val number: String,
    val tenantSlug: String,
    val tenantName: String,
    /** Yang ditagih: nama pelanggan (tagihan tenant) atau nama tenant (langganan SaaS). */
    val payerName: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val amount: BigDecimal,
    /** Nama status tagihan, mis. "ISSUED"/"PAID"/"OVERDUE"/"VOID". */
    val status: String,
    val dueDate: LocalDate,
    val paidAt: Instant?,
    val payableOnline: Boolean,
    val payMethod: String?,
    val vaChannel: String?,
    val vaNumber: String?,
    val vaName: String?,
    val vaExpiresAt: Instant?,
    /** String QRIS mentah (dirender jadi kode QR di klien). */
    val qrContent: String?,
    val qrExpiresAt: Instant?,
    val manual: PublicManualInstructionsView?,
)

/** Instruksi bayar manual tenant (gateway MANUAL) — non-rahasia; gambar QRIS diambil terpisah. */
data class PublicManualInstructionsView(
    val transferEnabled: Boolean,
    val bankName: String?,
    val accountNumber: String?,
    val accountHolder: String?,
    val qrisEnabled: Boolean,
    val qrisImageAvailable: Boolean,
)
