package com.duluin.ftth.portal.application.service

import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.portal.application.port.inbound.PortalAuthTokens
import com.duluin.ftth.portal.application.port.inbound.PortalAuthenticationUseCase
import com.duluin.ftth.portal.application.port.inbound.PortalLoginCommand
import com.duluin.ftth.portal.application.port.inbound.PortalLoginResult
import com.duluin.ftth.portal.application.port.inbound.PortalTenantChoice
import com.duluin.ftth.portal.application.port.outbound.PortalRefreshTokenRepository
import com.duluin.ftth.portal.domain.model.PortalCredential
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.springframework.stereotype.Service

/**
 * Orkestrasi autentikasi portal. SENGAJA tidak `@Transactional`: satu percobaan masuk bisa
 * menyentuh beberapa tenant, sedangkan setiap pembacaan ber-RLS harus berjalan dalam
 * [TenantContext] miliknya sendiri. Jadi service ini memasang tenant satu per satu lalu
 * memanggil worker transaksional [PortalTenantScopedAuthenticator].
 *
 * ## Kenapa passwordnya diperiksa ke SEMUA kandidat lebih dulu
 *
 * Pelanggan cukup mengetik satu identitas (email / nomor HP / username) tanpa menyebut ISP.
 * Indeks identitas bisa menunjuk ke lebih dari satu ISP — sah, karena satu orang boleh
 * berlangganan di dua tempat. Godaannya adalah langsung menampilkan daftar ISP dan bertanya
 * "yang mana?", tapi itu menjadikan layar masuk alat intip: mengetik nomor HP orang lain
 * cukup untuk mengetahui ia pelanggan siapa. Karena itu password diverifikasi ke seluruh
 * kandidat DULU; satu kecocokan ⇒ langsung masuk (tak ada pertanyaan sama sekali), lebih
 * dari satu ⇒ baru pilihan ditawarkan, kepada orang yang sudah membuktikan tahu passwordnya.
 *
 * Konsekuensi yang diterima sadar: bila seseorang memakai email sama di dua ISP dengan
 * password BERBEDA, ia hanya sampai ke salah satunya — untuk yang lain ia memakai username
 * ISP itu (identitas yang tak kembar) atau memulihkan passwordnya.
 */
@Service
class PortalAuthenticationService(
    private val tenantApi: TenantApi,
    private val authenticator: PortalTenantScopedAuthenticator,
    private val identityResolver: PortalIdentityResolver,
    private val refreshTokens: PortalRefreshTokenRepository,
) : PortalAuthenticationUseCase {

    override fun login(command: PortalLoginCommand): PortalLoginResult {
        val identifier = command.identifier.trim()
        if (identifier.isEmpty() || command.password.isEmpty()) throw invalidCredentials()

        val candidates = resolveCandidates(identifier, command.tenantSlug)
        // Verifikasi ke semua kandidat, lalu barulah dilihat hasilnya — lihat KDoc kelas.
        val matches = candidates.mapNotNull { candidate ->
            TenantContext.runAs(candidate.tenant.id) {
                authenticator.verifyPassword(candidate.customerId, command.password)
            }?.let { Match(candidate.tenant, it) }
        }
        if (matches.isEmpty()) throw invalidCredentials()

        val usable = matches.filter { it.credential.active }
        // Password benar tapi semua akunnya dimatikan ISP — barulah aman menyebut alasannya,
        // karena yang bertanya sudah terbukti pemilik akun.
        if (usable.isEmpty()) throw AuthenticationException("Akun portal dinonaktifkan")

        return usable.singleOrNull()
            ?.let { PortalLoginResult.Authenticated(issue(it)) }
            ?: PortalLoginResult.ChooseTenant(usable.map { PortalTenantChoice(it.tenant.slug, it.tenant.name) })
    }

    override fun refresh(refreshToken: String): PortalAuthTokens {
        val presented = refreshTokens.findByTokenHash(PortalTokens.sha256(refreshToken))
            ?.takeIf { it.isActive() }
            ?: throw AuthenticationException("Refresh token tidak valid atau kadaluarsa")

        return TenantContext.runAs(presented.tenantId) {
            authenticator.rotateAndIssue(presented)
        }
    }

    override fun logout(refreshToken: String) {
        val presented = refreshTokens.findByTokenHash(PortalTokens.sha256(refreshToken)) ?: return
        TenantContext.runAs(presented.tenantId) {
            authenticator.revoke(presented)
        }
    }

    /**
     * Kandidat dari indeks identitas, ditambah jaring pengaman untuk jalur ber-slug.
     *
     * Batas [MAX_CANDIDATES] penting di sini: tiap kandidat berarti satu verifikasi BCrypt
     * yang memang sengaja lambat. Batasnya jauh di atas jumlah masuk akal (berlangganan di 5
     * ISP sekaligus).
     */
    private fun resolveCandidates(identifier: String, requestedSlug: String?): List<PortalCandidate> {
        val slug = requestedSlug?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        // Slug tak dikenal → pesan sama dengan password salah (jangan bocorkan ISP mana ada).
        val requestedTenant = slug?.let { tenantApi.findBySlug(it) ?: throw invalidCredentials() }
        if (requestedTenant != null && requestedTenant.status != TenantStatus.ACTIVE) {
            throw AuthenticationException("Layanan tenant sedang tidak aktif")
        }

        val resolved = identityResolver.resolve(identifier, requestedTenant, MAX_CANDIDATES)
        if (resolved.isNotEmpty() || requestedTenant == null) return resolved

        // Jaring pengaman untuk jalur ber-slug: kredensial yang dibuat di luar jalur normal
        // belum tentu terindeks. Username tetap harus bisa dipakai masuk selama ISP-nya
        // disebut — tanpa ini, satu baris indeks yang hilang berarti pelanggan terkunci.
        val byLogin = TenantContext.runAs(requestedTenant.id) { authenticator.findByLogin(identifier) }
        return byLogin?.let { listOf(PortalCandidate(requestedTenant, it.customerId)) } ?: emptyList()
    }

    private fun issue(match: Match): PortalAuthTokens =
        TenantContext.runAs(match.tenant.id) { authenticator.issueFor(match.credential) }

    private fun invalidCredentials() = AuthenticationException("Login atau password salah")

    private data class Match(val tenant: TenantRef, val credential: PortalCredential)

    private companion object {
        const val MAX_CANDIDATES = 5
    }
}
