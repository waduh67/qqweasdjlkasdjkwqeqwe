package com.duluin.ftth.portal.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Kanal tempat kode pemulihan dikirim. Dicatat untuk audit & kalimat bantuan di UI. */
enum class PortalResetChannel { EMAIL, WHATSAPP }

/**
 * Satu permintaan "lupa password": kode sekali-pakai yang ditukar dengan password baru.
 *
 * Kode disimpan sebagai HASH — bentuk terbacanya hanya pernah ada di pesan yang dikirim
 * ke pelanggan, tak pernah di DB maupun di riwayat broadcast. Karena kodenya cuma
 * [CODE_DIGITS] digit (harus enak dibacakan lewat WhatsApp), kekuatannya TIDAK datang
 * dari panjangnya melainkan dari tiga batas di bawah: umur pendek, jumlah percobaan
 * terbatas, dan sekali pakai. Tanpa ketiganya, 6 digit bisa ditebak paksa dalam hitungan
 * menit.
 *
 * [identifier] mengikat kode ke identitas yang DIKETIK pemohon, sehingga kode yang bocor
 * tak bisa dipakai atas nama orang lain.
 */
class PortalPasswordReset private constructor(
    val id: UUID,
    val tenantId: UUID,
    val customerId: UUID,
    val identifier: String,
    val codeHash: String,
    val channel: PortalResetChannel,
    val expiresAt: Instant,
    attempts: Int,
    consumedAt: Instant?,
) {
    /** Percobaan penukaran yang gagal. Mencapai [MAX_ATTEMPTS] = kode mati. */
    var attempts: Int = attempts
        private set

    /** Non-null = kode sudah ditukar; tak bisa dipakai lagi. */
    var consumedAt: Instant? = consumedAt
        private set

    fun isUsable(now: Instant = Instant.now()): Boolean =
        consumedAt == null && attempts < MAX_ATTEMPTS && now.isBefore(expiresAt)

    fun recordFailedAttempt() {
        attempts += 1
    }

    fun consume(at: Instant = Instant.now()) {
        if (consumedAt == null) consumedAt = at
    }

    /** Dipakai saat permintaan baru datang: kode lama langsung dianggap habis terpakai. */
    fun revoke(at: Instant = Instant.now()) = consume(at)

    companion object {
        /** Cukup pendek untuk didikte lewat telepon, cukup panjang berpasangan dengan batas di bawah. */
        const val CODE_DIGITS = 6

        /** Lebih dari cukup untuk membuka WhatsApp/email, terlalu pendek untuk ditebak paksa. */
        val TTL: Duration = Duration.ofMinutes(15)

        /** Salah ketik wajar (2–3×) tetap dimaafkan; selebihnya sudah bukan pelanggan asli. */
        const val MAX_ATTEMPTS = 5

        /** Jeda minimum antar-permintaan — mencegah kotak masuk/WA pelanggan dibanjiri. */
        val RESEND_COOLDOWN: Duration = Duration.ofSeconds(60)

        @Suppress("LongParameterList")
        fun issue(
            tenantId: UUID,
            customerId: UUID,
            identifier: String,
            codeHash: String,
            channel: PortalResetChannel,
            now: Instant = Instant.now(),
        ): PortalPasswordReset = PortalPasswordReset(
            id = UuidV7.generate(),
            tenantId = tenantId,
            customerId = customerId,
            identifier = identifier,
            codeHash = codeHash,
            channel = channel,
            expiresAt = now.plus(TTL),
            attempts = 0,
            consumedAt = null,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            customerId: UUID,
            identifier: String,
            codeHash: String,
            channel: PortalResetChannel,
            expiresAt: Instant,
            attempts: Int,
            consumedAt: Instant?,
        ): PortalPasswordReset =
            PortalPasswordReset(id, tenantId, customerId, identifier, codeHash, channel, expiresAt, attempts, consumedAt)
    }
}
