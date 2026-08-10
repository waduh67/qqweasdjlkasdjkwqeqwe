package com.duluin.ftth.iam.application.port.outbound

import java.time.Instant

/**
 * Mesin TOTP (RFC 6238) — dipisahkan sebagai port supaya lapisan application bisa
 * diuji dengan waktu dan kode yang ditentukan, tanpa menunggu jendela 30 detik nyata.
 */
interface TotpEngine {

    /** Rahasia baru dalam Base32 (huruf besar, tanpa padding) — bentuk yang dipahami semua aplikasi autentikator. */
    fun newSecret(): String

    /**
     * Cocokkan [code] dengan [secret]. Mengembalikan LANGKAH WAKTU yang cocok, bukan
     * sekadar true/false: pemanggil memakai angka itu untuk menolak pemakaian ulang kode
     * yang sama di dalam jendelanya. `null` = tidak cocok.
     */
    fun verify(secret: String, code: String, at: Instant = Instant.now()): Long?

    /** URI `otpauth://` untuk dijadikan QR — isinya rahasia, jadi tak boleh masuk log. */
    fun provisioningUri(secret: String, account: String, issuer: String): String
}
