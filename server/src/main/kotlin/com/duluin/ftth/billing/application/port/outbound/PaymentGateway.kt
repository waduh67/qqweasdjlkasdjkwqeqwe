package com.duluin.ftth.billing.application.port.outbound

import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.common.domain.error.ConflictException
import org.springframework.modulith.NamedInterface
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Kontrak payment gateway yang provider-agnostik: satu antarmuka untuk semua
 * penyedia (Pivot dan manual/transfer). Modul billing hanya tahu antarmuka ini;
 * adapter konkret hidup di `adapter/outbound/gateway`.
 *
 * [provider] adalah nama kanonik penyedia (mis. `MANUAL`), dipakai registry untuk
 * memilih adapter dan disimpan di tagihan/pembayaran.
 *
 * Adapter tetap singleton stateless: kredensial per-tenant TIDAK dipegang adapter, melainkan
 * disuntikkan lewat [ResolvedGatewayContext] tiap panggilan (hasil resolusi baris config tenant).
 * Inilah alasan kedua method menerima `ctx` — satu adapter melayani banyak tenant.
 *
 * Bagian dari named interface `gateway` (Spring Modulith): mesin payment gateway ini di-expose
 * agar module `platformbilling` (penagihan langganan SaaS) memakai ulang registry & adapter yang
 * sama, tanpa menembus batas enkapsulasi billing lain. Lihat [ModularityTests].
 */
@NamedInterface("gateway")
interface PaymentGateway {

    val provider: String

    /** Buat charge untuk sebuah tagihan memakai kredensial tenant di [ctx]; hasilnya dilekatkan ke tagihan. */
    fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult

    /**
     * Terjemahkan callback (webhook) menjadi settlement bila sah & bisa diurai, memakai token
     * verifikasi tenant di [ctx]. `null` berarti callback ditolak (tanda tangan salah) atau tak
     * bisa dipahami — pemanggil membalas 4xx tanpa menyentuh tagihan.
     */
    fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext): PaymentSettlement?

    /**
     * Paksa sesi bayar [paymentSessionId] menjadi [chargeStatus] di lingkungan **sandbox** penyedia —
     * alat uji agar alur bayar bisa dicoba tanpa transaksi bank/e-wallet sungguhan. Penyedia
     * mengirim webhook seperti pembayaran nyata, jadi pelunasan tetap lewat [parseCallback]
     * (method ini TIDAK menyentuh tagihan). Bawaan: menolak — hanya penyedia yang punya endpoint
     * simulasi (Pivot) yang meng-override.
     */
    fun simulateCharge(paymentSessionId: String, chargeStatus: SimulatedChargeStatus, ctx: ResolvedGatewayContext): Unit =
        throw ConflictException("Penyedia '$provider' tidak mendukung simulasi pembayaran")

    /**
     * Perintahkan penyedia mengembalikan uang pelanggan. Hasilnya JARANG langsung final — penyedia
     * mengantre transfer balik dan mengabarkannya lewat callback — jadi kembaliannya cuma referensi
     * + status mentah; yang mengubah tagihan adalah jalur rekonsiliasi, bukan method ini.
     *
     * Bawaan: menolak. Penyedia MANUAL (transfer tangan) tak punya API balik; pengembaliannya
     * dicatat operator dan dinyatakan selesai lewat jalur manual.
     */
    fun refund(request: RefundRequest, ctx: ResolvedGatewayContext): RefundResult =
        throw ConflictException("Penyedia '$provider' tidak mendukung pengembalian dana otomatis")
}

/** Status akhir yang dipaksakan ke sesi bayar saat simulasi (sandbox). */
@NamedInterface("gateway")
enum class SimulatedChargeStatus { SUCCESS, EXPIRED }

/**
 * Permintaan pengembalian dana ke penyedia.
 *
 * [paymentSessionId] adalah referensi charge yang dulu melunasi tagihan (`Invoice.gatewayRef`) —
 * penyedia mengembalikan uang ke instrumen yang dipakai membayar, jadi tanpa ini tak ada tujuan.
 * [referenceId] adalah id baris refund kita, dikirim sebagai `clientReferenceId` supaya callback
 * bisa dipulangkan ke barisnya. [fullAmount] menandai pengembalian seluruh nilai charge —
 * dibedakan dari nominal yang kebetulan sama besar karena penyedia menghitungnya sendiri.
 */
@NamedInterface("gateway")
data class RefundRequest(
    val paymentSessionId: String,
    val amount: BigDecimal,
    val fullAmount: Boolean,
    val reason: String,
    val referenceId: String,
    val description: String? = null,
)

/**
 * Hasil perintah refund. [status] status mentah penyedia (mis. `PENDING`, `WAITING_BANK_TRANSFER`,
 * `SUCCESS`); [settled] true hanya bila penyedia menyatakan uangnya SUDAH kembali di respons yang
 * sama — selain itu pemanggil menunggu callback.
 */
@NamedInterface("gateway")
data class RefundResult(
    val reference: String?,
    val status: String?,
    val settled: Boolean = false,
)

/**
 * Permintaan membuat charge untuk sebuah tagihan.
 *
 * [dueDate] = jatuh tempo tagihan. Penyedia yang mendukung batas waktu sesi bayar (mis. Pivot mode
 * STRICT) memakainya sebagai kedaluwarsa tautan bayar; `null` = biarkan penyedia pakai default.
 * Penyedia yang tak punya konsep ini (manual/transfer) mengabaikannya.
 *
 * [method] memilih instrumen bayar in-app (mode API): `VIRTUAL_ACCOUNT` atau `QR`. Bila diisi,
 * penyedia membuat charge mode-API yang mengembalikan instruksi bayar (nomor VA / string QRIS)
 * langsung di [ChargeResult] — tanpa redirect. `null` = perilaku lama (penyedia bebas memilih;
 * Pivot memakai halaman ter-host REDIRECT). [vaChannel] bank tujuan VA (mis. `BRI`), wajib bila
 * [method] = `VIRTUAL_ACCOUNT`.
 */
@NamedInterface("gateway")
data class ChargeRequest(
    val invoiceNumber: String,
    val amount: BigDecimal,
    val customerName: String,
    val customerEmail: String?,
    val description: String,
    val dueDate: LocalDate? = null,
    val method: String? = null,
    val vaChannel: String? = null,
)

/**
 * Hasil charge. [gatewayRef] & [payUrl] tetap ada untuk kompatibilitas (REDIRECT/legacy).
 * Untuk charge mode-API in-app, [method] menandai instrumen dan salah satu dari [virtualAccount]
 * / [qr] terisi dengan instruksi bayar yang ditampilkan langsung di aplikasi. [status] adalah
 * status charge mentah dari penyedia (mis. `WAITING_FOR_USER_ACTION`).
 */
@NamedInterface("gateway")
data class ChargeResult(
    val provider: String,
    val gatewayRef: String?,
    val payUrl: String?,
    val status: String? = null,
    val method: String? = null,
    val virtualAccount: VaInstruction? = null,
    val qr: QrInstruction? = null,
)

/** Instruksi Virtual Account untuk ditampilkan in-app: nomor VA + bank + kedaluwarsa. */
@NamedInterface("gateway")
data class VaInstruction(
    val channel: String?,
    val number: String,
    val name: String?,
    val expiresAt: Instant?,
)

/** Instruksi QRIS untuk ditampilkan in-app: [content] = string QRIS yang dirender jadi kode QR. */
@NamedInterface("gateway")
data class QrInstruction(
    val content: String,
    val url: String?,
    val expiresAt: Instant?,
)

/** Callback mentah dari penyedia — header (untuk verifikasi tanda tangan) + body apa adanya. */
@NamedInterface("gateway")
data class GatewayCallback(
    val headers: Map<String, String>,
    val rawBody: String,
)

/** Pelunasan yang sudah tervalidasi, siap diterapkan ke tagihan. */
@NamedInterface("gateway")
data class PaymentSettlement(
    val invoiceNumber: String,
    val gatewayRef: String?,
    val amount: BigDecimal,
    val paidAt: Instant,
    val provider: String,
)
