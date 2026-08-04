package com.duluin.ftth.billing.application.port.outbound

import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import org.springframework.modulith.NamedInterface
import java.math.BigDecimal
import java.time.Instant

/**
 * Kontrak payment gateway yang provider-agnostik: satu antarmuka untuk semua
 * penyedia (manual/transfer, dan kelak Midtrans/Xendit/dsb). Modul billing hanya
 * tahu antarmuka ini; adapter konkret hidup di `adapter/outbound/gateway`.
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

/** Permintaan membuat charge untuk sebuah tagihan. */
@NamedInterface("gateway")
data class ChargeRequest(
    val invoiceNumber: String,
    val amount: BigDecimal,
    val customerName: String,
    val customerEmail: String?,
    val description: String,
)

/** Hasil charge: referensi & tautan bayar bila penyedia menyediakannya. */
@NamedInterface("gateway")
data class ChargeResult(
    val provider: String,
    val gatewayRef: String?,
    val payUrl: String?,
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
