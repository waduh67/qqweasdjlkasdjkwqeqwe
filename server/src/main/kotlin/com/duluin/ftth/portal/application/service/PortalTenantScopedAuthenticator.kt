package com.duluin.ftth.portal.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.portal.application.port.inbound.PortalAuthTokens
import com.duluin.ftth.portal.application.port.inbound.PortalProfileView
import com.duluin.ftth.portal.application.port.outbound.PortalAccessTokenIssuer
import com.duluin.ftth.portal.application.port.outbound.PortalCredentialRepository
import com.duluin.ftth.portal.application.port.outbound.PortalPasswordHasher
import com.duluin.ftth.portal.application.port.outbound.PortalRefreshTokenRepository
import com.duluin.ftth.portal.domain.model.PortalCredential
import com.duluin.ftth.portal.domain.model.PortalRefreshToken
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Bagian autentikasi portal yang berjalan DI DALAM tenant context yang sudah dipasang
 * [PortalAuthenticationService] (via `TenantContext.runAs`). Batas `@Transactional` di
 * sini penting: session Hibernate baru terbuka setelah tenant ter-set, sehingga query
 * kredensial ter-scope tenant yang benar (RLS).
 *
 * Perannya sengaja sempit: MEMERIKSA dan MENERBITKAN, bukan memutuskan. Keputusan "identitas
 * ini milik siapa dan di ISP mana" berada di [PortalAuthenticationService], karena satu
 * percobaan masuk bisa menyentuh beberapa tenant sekaligus — sesuatu yang menurut definisi
 * tak bisa dijawab dari dalam satu tenant context.
 */
@Service
@Transactional
class PortalTenantScopedAuthenticator(
    private val credentials: PortalCredentialRepository,
    private val refreshTokens: PortalRefreshTokenRepository,
    private val passwordHasher: PortalPasswordHasher,
    private val accessTokenIssuer: PortalAccessTokenIssuer,
    private val securityProperties: SecurityProperties,
    private val customerApi: CustomerApi,
    private val tenantApi: TenantApi,
    private val events: ApplicationEventPublisher,
) {
    /**
     * Kredensial pelanggan ini BILA passwordnya cocok; null bila tidak (termasuk bila
     * pelanggan tak punya kredensial sama sekali).
     *
     * Status aktif SENGAJA tidak diperiksa di sini melainkan oleh pemanggil, setelah password
     * terbukti. Urutan itu penting: memeriksa aktif lebih dulu membuat "akun dinonaktifkan"
     * bisa dipancing tanpa tahu password — pembeda yang cukup untuk memetakan siapa saja yang
     * punya akun di sebuah ISP.
     */
    @Transactional(readOnly = true)
    fun verifyPassword(customerId: UUID, rawPassword: String): PortalCredential? =
        credentials.findByCustomerId(customerId)
            ?.takeIf { passwordHasher.matches(rawPassword, it.passwordHash) }

    /** Cari kredensial menurut username — jalur cadangan saat indeks identitas belum terisi. */
    @Transactional(readOnly = true)
    fun findByLogin(rawLogin: String): PortalCredential? =
        normalizeLogin(rawLogin)?.let { credentials.findByLogin(it) }

    /** Terbitkan token untuk kredensial yang passwordnya SUDAH terverifikasi pemanggil. */
    fun issueFor(credential: PortalCredential): PortalAuthTokens = issue(credential, "portal.auth.login")

    fun rotateAndIssue(presented: PortalRefreshToken): PortalAuthTokens {
        presented.revoke()
        refreshTokens.save(presented)
        val credential = credentials.findByCustomerId(presented.customerId)?.takeIf { it.active }
            ?: throw AuthenticationException("Sesi portal tidak valid")
        return issue(credential, "portal.auth.refresh")
    }

    fun revoke(presented: PortalRefreshToken) {
        presented.revoke()
        refreshTokens.save(presented)
    }

    private fun issue(credential: PortalCredential, action: String): PortalAuthTokens {
        val tenantId = TenantContext.tenantId()
        val customer = customerApi.findCustomer(credential.customerId)
            ?: throw AuthenticationException("Pelanggan tidak ditemukan")
        val tenant = tenantApi.findById(tenantId)
            ?: throw AuthenticationException("Tenant tidak ditemukan")

        val access = accessTokenIssuer.issue(credential.customerId, tenantId, credential.login, customer.name)

        val rawRefresh = PortalTokens.random()
        val refreshExpiresAt = Instant.now().plus(securityProperties.refreshTokenTtl)
        refreshTokens.save(
            PortalRefreshToken.issue(tenantId, credential.customerId, PortalTokens.sha256(rawRefresh), refreshExpiresAt),
        )

        events.publishEvent(
            AuditTrailEvent(
                tenantId = tenantId,
                actorId = credential.customerId,
                actorEmail = credential.login,
                action = action,
                entityType = "PortalCredential",
                entityId = credential.customerId.toString(),
            ),
        )

        return PortalAuthTokens(
            accessToken = access.value,
            accessTokenExpiresAt = access.expiresAt,
            refreshToken = rawRefresh,
            refreshTokenExpiresAt = refreshExpiresAt,
            customer = PortalProfileView(
                customerId = credential.customerId,
                tenantId = tenantId,
                tenantSlug = tenant.slug,
                code = customer.code,
                name = customer.name,
                login = credential.login,
                phone = customer.phone,
                status = customer.status,
            ),
        )
    }

    private fun normalizeLogin(raw: String): String? =
        runCatching { PortalCredential.normalizeLogin(raw) }.getOrNull()
}
