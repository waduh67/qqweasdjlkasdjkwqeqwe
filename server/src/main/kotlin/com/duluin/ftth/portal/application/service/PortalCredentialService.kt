package com.duluin.ftth.portal.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.portal.application.port.inbound.ManagePortalCredentialUseCase
import com.duluin.ftth.portal.application.port.inbound.PortalCredentialProvisioned
import com.duluin.ftth.portal.application.port.inbound.PortalCredentialSummary
import com.duluin.ftth.portal.application.port.outbound.PortalCredentialRepository
import com.duluin.ftth.portal.application.port.outbound.PortalPasswordHasher
import com.duluin.ftth.portal.application.port.outbound.PortalRefreshTokenRepository
import com.duluin.ftth.portal.domain.model.PortalCredential
import com.duluin.ftth.portal.security.CurrentPortalCustomer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Kelola kredensial portal pelanggan. Dua sisi pemakai (lihat [ManagePortalCredentialUseCase]):
 * operator menyiapkan/mereset akses, pelanggan mengganti password sendiri.
 *
 * Invarian penting:
 *  - Satu pelanggan ≤ satu kredensial (find-or-create by customerId).
 *  - Login unik per tenant (dijaga di sini + unique index DB); RLS memastikan lookup
 *    ter-scope tenant aktif.
 *  - Reset password / nonaktifkan mencabut semua refresh-token pelanggan agar sesi lama mati.
 */
@Service
@Transactional
class PortalCredentialService(
    private val credentials: PortalCredentialRepository,
    private val refreshTokens: PortalRefreshTokenRepository,
    private val passwordHasher: PortalPasswordHasher,
    private val customerApi: CustomerApi,
    private val currentPortalCustomer: CurrentPortalCustomer,
    private val currentUserProvider: CurrentUserProvider,
    private val events: ApplicationEventPublisher,
) : ManagePortalCredentialUseCase {

    @Transactional(readOnly = true)
    override fun summaryFor(customerId: UUID): PortalCredentialSummary? =
        credentials.findByCustomerId(customerId)?.let {
            PortalCredentialSummary(it.customerId, it.login, it.active)
        }

    override fun provisionFor(customerId: UUID, login: String?, password: String?): PortalCredentialProvisioned {
        val customer = customerApi.findCustomer(customerId)
            ?: throw NotFoundException("Pelanggan tidak ditemukan")

        // Login default = kode pelanggan (mis. "cust-000001"); operator boleh menimpanya.
        val desiredLogin = normalizeLoginOrThrow(login?.takeIf { it.isNotBlank() } ?: customer.code)
        assertLoginFree(desiredLogin, ownedBy = customerId)

        val (rawPassword, generatedPassword) = resolvePassword(password)
        val hash = passwordHasher.hash(rawPassword)

        val existing = credentials.findByCustomerId(customerId)
        val credential = existing?.apply {
            changeLogin(desiredLogin)
            changePassword(hash)
            enable()
        } ?: PortalCredential.create(customerId, desiredLogin, hash)

        val saved = credentials.save(credential)
        // Kredensial baru/di-reset → matikan sesi lama.
        refreshTokens.revokeAllForCustomer(customerId)
        auditOperator("portal.credential.provision", customerId, saved.login)

        return PortalCredentialProvisioned(customerId, saved.login, saved.active, generatedPassword)
    }

    override fun resetPassword(customerId: UUID, newPassword: String?): PortalCredentialProvisioned {
        val credential = credentials.findByCustomerId(customerId)
            ?: throw NotFoundException("Kredensial portal belum dibuat untuk pelanggan ini")

        val (rawPassword, generatedPassword) = resolvePassword(newPassword)
        credential.changePassword(passwordHasher.hash(rawPassword))
        val saved = credentials.save(credential)
        refreshTokens.revokeAllForCustomer(customerId)
        auditOperator("portal.credential.reset-password", customerId, saved.login)

        return PortalCredentialProvisioned(customerId, saved.login, saved.active, generatedPassword)
    }

    override fun setEnabled(customerId: UUID, enabled: Boolean): PortalCredentialSummary {
        val credential = credentials.findByCustomerId(customerId)
            ?: throw NotFoundException("Kredensial portal belum dibuat untuk pelanggan ini")

        if (enabled) credential.enable() else credential.disable()
        val saved = credentials.save(credential)
        // Nonaktif → cabut sesi berjalan supaya efeknya langsung.
        if (!enabled) refreshTokens.revokeAllForCustomer(customerId)
        auditOperator(if (enabled) "portal.credential.enable" else "portal.credential.disable", customerId, saved.login)

        return PortalCredentialSummary(saved.customerId, saved.login, saved.active)
    }

    override fun changeOwnPassword(currentPassword: String, newPassword: String) {
        val principal = currentPortalCustomer.current()
        val credential = credentials.findByCustomerId(principal.customerId)
            ?: throw NotFoundException("Kredensial portal tidak ditemukan")
        if (!credential.active) throw AccessDeniedException("Akun portal dinonaktifkan")
        if (!passwordHasher.matches(currentPassword, credential.passwordHash)) {
            throw AuthenticationException("Password saat ini salah")
        }
        validatePassword(newPassword)

        credential.changePassword(passwordHasher.hash(newPassword))
        credentials.save(credential)
        // Ganti password mandiri mengakhiri seluruh sesi (termasuk yang sekarang) — login ulang.
        refreshTokens.revokeAllForCustomer(principal.customerId)

        events.publishEvent(
            AuditTrailEvent(
                tenantId = principal.tenantId,
                actorId = principal.customerId,
                actorEmail = principal.login,
                action = "portal.credential.self-change-password",
                entityType = "PortalCredential",
                entityId = principal.customerId.toString(),
            ),
        )
    }

    /** Password kosong = server men-generate sementara (sekali-tampil); jika diisi, validasi panjang. */
    private fun resolvePassword(raw: String?): ResolvedPassword {
        if (raw.isNullOrBlank()) {
            val generated = PortalTokens.readablePassword()
            return ResolvedPassword(generated, generated)
        }
        validatePassword(raw)
        return ResolvedPassword(raw, null)
    }

    private fun assertLoginFree(login: String, ownedBy: UUID) {
        val holder = credentials.findByLogin(login) ?: return
        if (holder.customerId != ownedBy) {
            throw ConflictException("Login \"$login\" sudah dipakai pelanggan lain")
        }
    }

    private fun normalizeLoginOrThrow(raw: String): String =
        runCatching { PortalCredential.normalizeLogin(raw) }
            .getOrElse { throw ValidationException(it.message ?: "Login portal tidak valid") }

    private fun validatePassword(raw: String) {
        if (raw.length < MIN_PASSWORD_LENGTH) {
            throw ValidationException("Password portal minimal $MIN_PASSWORD_LENGTH karakter")
        }
    }

    private fun auditOperator(action: String, customerId: UUID, login: String) {
        val operator = currentUserProvider.currentOrNull()
        events.publishEvent(
            AuditTrailEvent(
                tenantId = TenantContext.tenantId(),
                actorId = operator?.userId,
                actorEmail = operator?.email,
                action = action,
                entityType = "PortalCredential",
                entityId = customerId.toString(),
                detail = mapOf("login" to login),
            ),
        )
    }

    /** [reveal] non-null HANYA saat password digenerate server (wajib dibagikan sekali ke pelanggan). */
    private data class ResolvedPassword(val raw: String, val reveal: String?)

    private companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}
