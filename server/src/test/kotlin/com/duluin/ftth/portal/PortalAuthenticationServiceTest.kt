package com.duluin.ftth.portal

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.portal.application.port.inbound.PortalLoginCommand
import com.duluin.ftth.portal.application.service.PortalAuthenticationService
import com.duluin.ftth.portal.application.service.PortalTenantScopedAuthenticator
import com.duluin.ftth.portal.domain.model.PortalCredential
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

/**
 * Menguji alur autentikasi portal end-to-end (service + authenticator) dengan fake:
 *  - login: slug + login + password benar → token; salah/nonaktif/slug tak dikenal → 401 seragam.
 *  - refresh: rotasi (token lama dicabut, terbit baru); token asing ditolak.
 *  - logout: mencabut token; audit terpublikasi saat login/refresh.
 */
class PortalAuthenticationServiceTest {

    private val tenantId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()
    private val tenant = TenantRef(tenantId, "jayanet", "Jaya Net", TenantStatus.ACTIVE)

    private val credentials = InMemoryPortalCredentialRepository()
    private val refreshTokens = RecordingPortalRefreshTokenRepository()
    private val hasher = PlainTextPortalPasswordHasher()
    private val issuer = StubPortalAccessTokenIssuer()
    private val customers = StubCustomerApi(
        CustomerRef(customerId, "CUST-000007", "Budi", "0811", null, Coordinate(0.0, 0.0), "ACTIVE"),
    )
    private val tenants = StubTenantApi(tenant)
    private val auditEvents = mutableListOf<AuditTrailEvent>()
    private val publisher = ApplicationEventPublisher { event -> if (event is AuditTrailEvent) auditEvents.add(event) }
    private val securityProperties = SecurityProperties(jwtSecret = "a".repeat(32), encryptionSecret = "b".repeat(32))

    private val authenticator = PortalTenantScopedAuthenticator(
        credentials, refreshTokens, hasher, issuer, securityProperties, customers, tenants, publisher,
    )
    private val service = PortalAuthenticationService(tenants, authenticator, refreshTokens)

    @AfterEach
    fun clearTenant() = TenantContext.clear()

    private fun seedCredential(login: String = "budi01", password: String = "rahasia123", disabled: Boolean = false) {
        TenantContext.runAs(tenantId) {
            val credential = PortalCredential.create(customerId, login, hasher.hash(password))
            if (disabled) credential.disable()
            credentials.save(credential)
        }
    }

    @Test
    fun `login benar menerbitkan token & profil serta mempublikasikan audit`() {
        seedCredential()

        val tokens = service.login(PortalLoginCommand("jayanet", "budi01", "rahasia123"))

        assertThat(tokens.accessToken).isEqualTo("access-budi01")
        assertThat(tokens.refreshToken).isNotBlank()
        assertThat(tokens.customer.customerId).isEqualTo(customerId)
        assertThat(tokens.customer.tenantSlug).isEqualTo("jayanet")
        assertThat(tokens.customer.code).isEqualTo("CUST-000007")
        assertThat(auditEvents.map { it.action }).containsExactly("portal.auth.login")
        // Refresh-token tersimpan sebagai hash (bukan nilai mentah yang dikembalikan).
        assertThat(refreshTokens.findByTokenHash(tokens.refreshToken)).isNull()
    }

    @Test
    fun `login dengan password salah gagal seragam`() {
        seedCredential()

        assertThatThrownBy { service.login(PortalLoginCommand("jayanet", "budi01", "salah")) }
            .isInstanceOf(AuthenticationException::class.java)
            .hasMessage("Login atau password salah")
    }

    @Test
    fun `login slug tak dikenal gagal seragam`() {
        seedCredential()

        assertThatThrownBy { service.login(PortalLoginCommand("tidakada", "budi01", "rahasia123")) }
            .isInstanceOf(AuthenticationException::class.java)
            .hasMessage("Login atau password salah")
    }

    @Test
    fun `login kredensial nonaktif ditolak`() {
        seedCredential(disabled = true)

        assertThatThrownBy { service.login(PortalLoginCommand("jayanet", "budi01", "rahasia123")) }
            .isInstanceOf(AuthenticationException::class.java)
    }

    @Test
    fun `login tenant suspended ditolak`() {
        tenants.replace(TenantRef(tenantId, "jayanet", "Jaya Net", TenantStatus.SUSPENDED))
        seedCredential()

        assertThatThrownBy { service.login(PortalLoginCommand("jayanet", "budi01", "rahasia123")) }
            .isInstanceOf(AuthenticationException::class.java)
    }

    @Test
    fun `refresh merotasi token lama dan menerbitkan baru`() {
        seedCredential()
        val first = service.login(PortalLoginCommand("jayanet", "budi01", "rahasia123"))
        auditEvents.clear()

        val second = service.refresh(first.refreshToken)

        assertThat(second.refreshToken).isNotEqualTo(first.refreshToken)
        assertThat(auditEvents.map { it.action }).containsExactly("portal.auth.refresh")
        // Token pertama sudah tak bisa dipakai lagi (dirotasi).
        assertThatThrownBy { service.refresh(first.refreshToken) }
            .isInstanceOf(AuthenticationException::class.java)
    }

    @Test
    fun `refresh token asing ditolak`() {
        assertThatThrownBy { service.refresh("token-tak-terdaftar") }
            .isInstanceOf(AuthenticationException::class.java)
    }

    @Test
    fun `logout mencabut token sehingga refresh berikutnya gagal`() {
        seedCredential()
        val tokens = service.login(PortalLoginCommand("jayanet", "budi01", "rahasia123"))

        service.logout(tokens.refreshToken)

        assertThatThrownBy { service.refresh(tokens.refreshToken) }
            .isInstanceOf(AuthenticationException::class.java)
    }

    @Test
    fun `logout token asing aman (no-op)`() {
        service.logout("token-tak-terdaftar")
    }

    private class StubTenantApi(private var tenant: TenantRef) : TenantApi {
        fun replace(next: TenantRef) {
            tenant = next
        }

        override fun findById(id: UUID): TenantRef? = tenant.takeIf { it.id == id }
        override fun findBySlug(slug: String): TenantRef? = tenant.takeIf { it.slug == slug }
        override fun requireById(id: UUID): TenantRef = findById(id) ?: throw UnsupportedOperationException()
        override fun platformTenantId(): UUID = throw UnsupportedOperationException()
        override fun findActiveTenantIds(): List<UUID> = throw UnsupportedOperationException()
        override fun ensureTenant(slug: String, name: String): TenantRef = throw UnsupportedOperationException()
        override fun suspend(id: UUID): TenantRef = throw UnsupportedOperationException()
        override fun activate(id: UUID): TenantRef = throw UnsupportedOperationException()
    }
}
