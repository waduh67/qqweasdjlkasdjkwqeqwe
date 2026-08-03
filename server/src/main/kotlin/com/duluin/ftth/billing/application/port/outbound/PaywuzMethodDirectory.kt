package com.duluin.ftth.billing.application.port.outbound

/**
 * Port keluar: daftar metode pembayaran aktif sebuah proyek Paywuz (untuk mengisi pilihan
 * metode per-tenant di UI). Terpisah dari [PaymentGateway] karena spesifik Paywuz — bukan
 * kapabilitas gateway generik. Diimplementasi adapter Paywuz (memakai API key tenant).
 */
interface PaywuzMethodDirectory {
    /** Metode aktif proyek pemilik [apiKey]; melempar bila Paywuz menolak permintaan. */
    fun listMethods(apiKey: String): List<PaywuzMethodInfo>
}

/** Satu metode pembayaran Paywuz: [code] dipakai saat charge, [name]/[type] untuk tampilan. */
data class PaywuzMethodInfo(
    val code: String,
    val name: String,
    val type: String,
)
