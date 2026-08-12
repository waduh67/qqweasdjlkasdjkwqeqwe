package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.iam.application.port.inbound.SelfSignupCommand
import com.duluin.ftth.iam.application.port.inbound.SelfSignupResult
import com.duluin.ftth.iam.application.port.inbound.SelfSignupUseCase
import com.duluin.ftth.iam.application.port.outbound.UserDirectory
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import kotlin.random.Random

/**
 * Pendaftaran mandiri ISP: MEMILIH kode ISP-nya sendiri dari nama, menjaga keunikan email di
 * depan, lalu memakai ulang [OnboardTenantUseCase] yang sudah teruji (tenant + role "Tenant
 * Admin" + admin awal + langganan trial lewat event). Tenant langsung AKTIF dengan periode
 * langganan awal satu bulan (de-facto trial) — platform admin bisa menyuspensi bila
 * menyalahgunakan.
 *
 * Kode ISP tak lagi diketik pendaftar. Ia kunci teknis (dipakai saat staf masuk), bukan
 * keputusan bisnis: menyerahkannya ke pendaftar hanya memindahkan kemungkinan bentrok ke wajah
 * orang yang tak punya cara memperbaikinya selain menebak nama lain.
 *
 * Endpoint publik ini rawan spam, jadi laju pendaftaran per-IP direm di adapter
 * ([com.duluin.ftth.iam.adapter.inbound.web.SignupController] →
 * [com.duluin.ftth.common.infrastructure.security.AttemptThrottle]). Rem itu menahan
 * banjir dari satu sumber, bukan pendaftar tekun berganti IP — untuk itu tetap tersedia
 * suspensi manual oleh platform admin.
 */
@Service
class SelfSignupService(
    private val tenantApi: TenantApi,
    private val userDirectory: UserDirectory,
    private val onboarding: OnboardTenantUseCase,
) : SelfSignupUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun signup(command: SelfSignupCommand): SelfSignupResult {
        val name = command.name.trim()
        val emailLower = command.adminEmail.trim().lowercase()

        // Keunikan email GLOBAL (1 email = 1 tenant): dicek lewat direktori pra-auth yang
        // memang diquery sebelum tenant context terpasang. Mencegah tenant yatim (admin gagal
        // dibuat karena email sudah ada di tenant lain). Ini SATU-SATUNYA bentrok yang masih
        // dilaporkan ke pendaftar, karena hanya ini yang bisa ia perbaiki sendiri.
        if (userDirectory.findTenantByEmail(emailLower) != null) {
            throw ConflictException("Email '${command.adminEmail.trim()}' sudah terdaftar. Silakan masuk.")
        }

        val result = onboardWithGeneratedSlug(name, command)
        return SelfSignupResult(
            slug = result.tenant.slug,
            name = result.tenant.name,
            adminEmail = command.adminEmail.trim(),
        )
    }

    /**
     * Mencoba beberapa kali karena keunikan slug pada akhirnya dijamin unique index, bukan cek
     * `findBySlug` di [nextSlug]: dua pendaftaran bernama sama yang tiba bersamaan sama-sama
     * melihat kode yang sama masih kosong. Yang kalah balapan mengulang dari pemilihan kode —
     * dan karena percobaan berikutnya menemukan kode itu SUDAH terisi, ia mendapat kode lain.
     */
    private fun onboardWithGeneratedSlug(name: String, command: SelfSignupCommand) =
        (1..MAX_ONBOARD_ATTEMPTS).firstNotNullOfOrNull { attempt ->
            try {
                onboarding.onboard(
                    OnboardTenantCommand(
                        slug = nextSlug(name, attempt),
                        name = name,
                        // Email dikirim ter-trim agar yang tersimpan == yang dicek keunikannya
                        // (spasi di tepi tak boleh mem-bypass cek di atas).
                        adminEmail = command.adminEmail.trim(),
                        adminName = command.adminName.trim(),
                        adminPassword = command.adminPassword,
                        // Publik tak menentukan harga; harga default global yang berlaku.
                        monthlyFee = null,
                    ),
                )
            } catch (e: DataIntegrityViolationException) {
                log.info("Kode ISP bentrok saat pendaftaran '{}' (percobaan {}), mencoba kode lain", name, attempt, e)
                null
            }
        } ?: throw ConflictException("Pendaftaran gagal karena sistem sedang sibuk. Silakan coba lagi.")

    /**
     * Kode ISP berikutnya untuk [name]. Percobaan terakhir langsung memakai akhiran acak alih-
     * alih menghitung `-2`, `-3`, … lagi: kalau sampai di situ, yang bentrok bukan lagi nama
     * kembar melainkan balapan, dan mengulangi urutan yang sama hanya akan bentrok lagi.
     */
    private fun nextSlug(name: String, attempt: Int): String {
        val base = slugify(name) ?: return randomSlug(FALLBACK_PREFIX)
        if (attempt >= MAX_ONBOARD_ATTEMPTS) return randomSlug(base)
        // Nama yang belum pernah dipakai mendapat kodenya apa adanya; sesudahnya bernomor,
        // supaya "PT Net Media Jaya" kedua menjadi `net-media-jaya-2` — masih terbaca manusia.
        return generateSequence(1) { it + 1 }
            .take(MAX_SUFFIX)
            .map { if (it == 1) base else "$base-$it" }
            .firstOrNull { tenantApi.findBySlug(it) == null }
            ?: randomSlug(base)
    }

    /**
     * Nama → kode: huruf kecil, apa pun selain huruf/angka jadi strip, strip di tepi dibuang,
     * lalu awalan non-huruf dipangkas karena domain mensyaratkan kode dimulai huruf ("PT 3
     * Saudara" → `saudara`, bukan `3-saudara` yang ditolak `Tenant.create`).
     *
     * Null bila tak tersisa apa pun yang layak — nama beraksara non-Latin sepenuhnya, misalnya.
     */
    private fun slugify(name: String): String? = name.lowercase()
        .replace(NON_SLUG, "-")
        .trim('-')
        .dropWhile { it !in 'a'..'z' }
        .trim('-')
        .take(MAX_BASE)
        .trim('-')
        .takeIf { it.length >= MIN_BASE }

    /** Akhiran acak, dipakai saat nama tak menghasilkan kode atau saat kode bernomor pun habis. */
    private fun randomSlug(base: String): String =
        "${base.take(MAX_BASE)}-" + (1..RANDOM_LEN).map { RANDOM_ALPHABET.random(Random) }.joinToString("")

    private companion object {
        val NON_SLUG = Regex("[^a-z0-9]+")

        /** Panjang kode dibatasi 63 karakter oleh domain; sisanya disisakan untuk akhiran. */
        const val MAX_BASE = 40
        const val MIN_BASE = 2
        const val MAX_SUFFIX = 50
        const val MAX_ONBOARD_ATTEMPTS = 3
        const val RANDOM_LEN = 6
        const val RANDOM_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

        /** Awalan untuk nama yang tak menyisakan satu huruf Latin pun. */
        const val FALLBACK_PREFIX = "isp"
    }
}
