package com.duluin.ftth.billing.application.port.outbound

import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
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
}

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
