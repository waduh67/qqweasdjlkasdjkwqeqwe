package com.duluin.ftth.portal.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.portal.application.port.outbound.PortalCredentialRepository
import com.duluin.ftth.portal.application.port.outbound.PortalPasswordHasher
import com.duluin.ftth.portal.application.port.outbound.PortalPasswordResetRepository
import com.duluin.ftth.portal.application.port.outbound.PortalRefreshTokenRepository
import com.duluin.ftth.portal.domain.model.PortalIdentifier
import com.duluin.ftth.portal.domain.model.PortalPasswordReset
import com.duluin.ftth.portal.domain.model.PortalResetChannel
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Bagian pemulihan password yang berjalan DI DALAM tenant context yang sudah dipasang
 * [PortalPasswordRecoveryService]. Alasannya sama persis dengan
 * [PortalTenantScopedAuthenticator]: tenant di-resolve saat session Hibernate pertama kali
 * dibuka, jadi batas `@Transactional` harus jatuh SETELAH `TenantContext.runAs` — bukan
 * membungkusnya. Satu permintaan pemulihan bisa menyentuh beberapa ISP, dan tiap ISP butuh
 * transaksinya sendiri.
 *
 * Pengiriman pesan sengaja TIDAK di sini: memanggil gateway WA/SMTP sambil memegang koneksi
 * DB berarti gateway yang lambat ikut menahan pool koneksi.
 */
@Service
@Transactional
class PortalTenantScopedRecovery(
    private val credentials: PortalCredentialRepository,
    private val refreshTokens: PortalRefreshTokenRepository,
    private val resets: PortalPasswordResetRepository,
    private val passwordHasher: PortalPasswordHasher,
    private val customerApi: CustomerApi,
) {
    /**
     * Terbitkan satu kode untuk seorang pelanggan, atau `null` bila pelanggan ini memang tak
     * bisa dipulihkan sekarang. Semua sebab "null" diperlakukan sama oleh pemanggil dan tak
     * pernah terlihat dari luar — lihat KDoc [PortalPasswordRecoveryService].
     */
    fun issueCode(customerId: UUID, identifier: String): IssuedResetCode? {
        // Tanpa kredensial tak ada password yang bisa dipulihkan; kredensial yang dimatikan
        // operator pun tak akan bisa dipakai masuk meski passwordnya diganti. Dua-duanya
        // urusan yang harus diselesaikan lewat ISP, bukan lewat kode.
        val credential = credentials.findByCustomerId(customerId)?.takeIf { it.active } ?: return null
        if (withinCooldown(customerId)) return null
        val customer = customerApi.findCustomer(customerId) ?: return null
        val target = chooseTarget(customer, identifier) ?: return null

        val code = PortalTokens.numericCode(PortalPasswordReset.CODE_DIGITS)
        // Kode lama mati begitu yang baru terbit — beberapa kode hidup berbarengan hanya
        // memperbanyak tebakan yang berlaku tanpa memberi manfaat apa pun.
        resets.revokeActiveFor(customerId)
        resets.save(
            PortalPasswordReset.issue(
                tenantId = TenantContext.tenantId(),
                customerId = customerId,
                identifier = canonicalIdentifier(identifier),
                codeHash = PortalTokens.sha256(code),
                channel = target.channel,
            ),
        )
        return IssuedResetCode(
            customerId = customerId,
            login = credential.login,
            recipientName = customer.name,
            channel = target.channel,
            destination = target.destination,
            code = code,
        )
    }

    /** Catat satu percobaan penukaran yang gagal, agar kode mati sebelum sempat ditebak habis. */
    fun recordFailedAttempt(reset: PortalPasswordReset) {
        reset.recordFailedAttempt()
        resets.save(reset)
    }

    /** Pasang password baru dan tutup kodenya. Dipanggil hanya setelah kode terbukti sah. */
    fun completeReset(reset: PortalPasswordReset, newPassword: String) {
        val credential = credentials.findByCustomerId(reset.customerId) ?: return
        credential.changePassword(passwordHasher.hash(newPassword))
        credentials.save(credential)
        // Password baru = semua sesi lama mati. Kalau akun ini memang sedang dibajak, sesi
        // penyusup ikut putus; kalau tidak, pelanggan cukup masuk ulang sekali.
        refreshTokens.revokeAllForCustomer(reset.customerId)
        reset.consume()
        resets.save(reset)
        // Sisa kode lain (mis. diminta dua kali sebelum sempat dipakai) ikut dimatikan.
        resets.revokeActiveFor(reset.customerId)
    }

    /**
     * Ke mana kode dikirim.
     *
     * Menghormati apa yang pelanggan ketik: menyebut emailnya ⇒ kode ke email, menyebut
     * nomornya ⇒ kode ke WhatsApp. Baru bila yang diketik username — yang tak memberi
     * petunjuk apa pun — email didahulukan dengan nomor sebagai cadangan.
     *
     * Tujuannya selalu kontak yang TERDAFTAR pada pelanggan, bukan yang diketik. Untuk
     * identitas yang cocok keduanya memang sama, tapi menuliskannya begini menutup pintu
     * bagi perubahan yang tanpa sengaja menjadikannya "kirim kode ke alamat mana pun".
     */
    private fun chooseTarget(customer: CustomerRef, identifier: String): ResetTarget? {
        val email = PortalIdentifier.email(customer.email)
        val phone = PortalIdentifier.phone(customer.phone)
        return when {
            email != null && PortalIdentifier.email(identifier) == email -> ResetTarget(PortalResetChannel.EMAIL, email)
            phone != null && PortalIdentifier.phone(identifier) == phone ->
                ResetTarget(PortalResetChannel.WHATSAPP, phone)

            email != null -> ResetTarget(PortalResetChannel.EMAIL, email)
            phone != null -> ResetTarget(PortalResetChannel.WHATSAPP, phone)
            else -> null
        }
    }

    /**
     * Bentuk kanonik dari apa yang diketik, untuk diikat ke kode. Diambil kandidat pertama:
     * daftar kandidat yang sama dipakai saat menukar kode nanti, jadi keduanya pasti bertemu.
     */
    private fun canonicalIdentifier(identifier: String): String =
        PortalIdentifier.candidates(identifier).firstOrNull()
            ?: identifier.trim().take(PortalIdentifier.MAX_LENGTH)

    private fun withinCooldown(customerId: UUID): Boolean {
        val last = resets.lastIssuedAtFor(customerId) ?: return false
        return Instant.now().isBefore(last.plus(PortalPasswordReset.RESEND_COOLDOWN))
    }

    private data class ResetTarget(val channel: PortalResetChannel, val destination: String)
}

/** Kode yang siap dikirim. Hanya hidup di memori — bentuk terbacanya tak pernah masuk DB. */
data class IssuedResetCode(
    val customerId: UUID,
    val login: String,
    val recipientName: String,
    val channel: PortalResetChannel,
    val destination: String,
    val code: String,
)
