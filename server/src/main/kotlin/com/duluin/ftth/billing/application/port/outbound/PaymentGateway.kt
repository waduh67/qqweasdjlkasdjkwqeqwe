package com.duluin.ftth.billing.application.port.outbound

import java.math.BigDecimal
import java.time.Instant

/**
 * Kontrak payment gateway yang provider-agnostik: satu antarmuka untuk semua
 * penyedia (manual/transfer, dan kelak Midtrans/Xendit/dsb). Modul billing hanya
 * tahu antarmuka ini; adapter konkret hidup di `adapter/outbound/gateway`.
 *
 * [provider] adalah nama kanonik penyedia (mis. `MANUAL`), dipakai registry untuk
 * memilih adapter dan disimpan di tagihan/pembayaran.
 */
interface PaymentGateway {

    val provider: String

    /** Buat charge untuk sebuah tagihan; hasilnya dilekatkan ke tagihan. */
    fun createCharge(request: ChargeRequest): ChargeResult

    /**
     * Terjemahkan callback (webhook) menjadi settlement bila sah & bisa diurai.
     * `null` berarti callback ditolak (tanda tangan salah) atau tak bisa dipahami —
     * pemanggil membalas 4xx tanpa menyentuh tagihan.
     */
    fun parseCallback(callback: GatewayCallback): PaymentSettlement?
}

/** Permintaan membuat charge untuk sebuah tagihan. */
data class ChargeRequest(
    val invoiceNumber: String,
    val amount: BigDecimal,
    val customerName: String,
    val customerEmail: String?,
    val description: String,
)

/** Hasil charge: referensi & tautan bayar bila penyedia menyediakannya. */
data class ChargeResult(
    val provider: String,
    val gatewayRef: String?,
    val payUrl: String?,
)

/** Callback mentah dari penyedia — header (untuk verifikasi tanda tangan) + body apa adanya. */
data class GatewayCallback(
    val headers: Map<String, String>,
    val rawBody: String,
)

/** Pelunasan yang sudah tervalidasi, siap diterapkan ke tagihan. */
data class PaymentSettlement(
    val invoiceNumber: String,
    val gatewayRef: String?,
    val amount: BigDecimal,
    val paidAt: Instant,
    val provider: String,
)
