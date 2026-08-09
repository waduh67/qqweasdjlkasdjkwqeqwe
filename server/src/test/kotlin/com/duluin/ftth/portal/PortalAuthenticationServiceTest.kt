package com.duluin.ftth.portal

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.portal.application.port.inbound.PortalAuthTokens
import com.duluin.ftth.portal.application.port.inbound.PortalLoginCommand
import com.duluin.ftth.portal.application.port.inbound.PortalLoginResult
import com.duluin.ftth.portal.application.service.PortalAuthenticationService
import com.duluin.ftth.portal.application.service.PortalIdentityResolver
import com.duluin.ftth.portal.application.service.PortalIdentitySyncService
import com.duluin.ftth.portal.application.service.PortalTenantScopedAuthenticator
import com.duluin.ftth.portal.domain.model.PortalCredential
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

/**
 * Menguji alur autentikasi portal end-to-end (service + resolver + authenticator) dengan fake:
 *  - login cukup dengan SATU identitas (username / email / nomor HP), tanpa menyebut ISP.
 *  - identitas yang dipakai di dua ISP: satu password cocok → langsung masuk; dua-duanya cocok
 *    → baru pilihan ISP ditawarkan (dan tak pernah sebelum password terbukti).
 *  - jalur lama (slug + username) tetap jalan, termasuk saat indeks identitas kosong.
 *  - refresh: rotasi (token lama dicabut, terbit baru); token asing ditolak.
 *  - logout: mencabut token; audit terpublikasi saat login/refresh.
 */
class PortalAuthenticationServiceTest {

    private val tenantId = UUID.randomUUID()
    private val otherTenantId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()
    private val otherCustomerId = UUID.randomUUID()
    private val tenant = TenantRef(tenantId, "jayanet", "Jaya Net", TenantStatus.ACTIVE)
    private val otherTenant = TenantRef(otherTenantId, "sinarnet", "Sinar Net", TenantStatus.ACTIVE)

    private val credentials = InMemoryPortalCredentialRepository()
    private val refreshTokens = RecordingPortalRefreshTokenRepository()
    private val hasher = PlainTextPortalPasswordHasher()
    private val issuer = StubPortalAccessTokenIssuer()
    private val customers = StubCustomerApi(
        CustomerRef(customerId, "CUST-000007", "Budi", "0811222333", "Budi@Mail.com", Coordinate(0.0, 0.0), "ACTIVE"),
        CustomerRef(otherCustomerId, "CUST-000008", "Budi", "0811222333", "budi@mail.com", Coordinate(0.0, 0.0), "ACTIVE"),
    )
    private val tenants = StubTenantApi(tenant, otherTenant)
    private val directory = InMemoryPortalIdentityDirectory()
    private val auditEvents = mutableListOf<AuditTrailEvent>()
    private val publisher = ApplicationEventPublisher { event -> if (event is AuditTrailEvent) auditEvents.add(event) }
    private val securityProperties = SecurityProperties(jwtSecret = "a".repeat(32), encryptionSecret = "b".repeat(32))

    private val authenticator = PortalTenantScopedAuthenticator(
        credentials, refreshTokens, hasher, issuer, securityProperties, customers, tenants, publisher,
    )
    private val identitySync = PortalIdentitySyncService(directory, credentials, customers)
    private val service = PortalAuthenticationService(
        tenants, authenticator, PortalIdentityResolver(directory, tenants), refreshTokens,
    )

    @AfterEach
    fun clearTenant() = TenantContext.clear()

    /** Seed kredensial + indeks identitasnya, seperti provisioning operator yang sesungguhnya. */
    private fun seedCredential(
        tenantId: UUID = this.tenantId,
        customerId: UUID = this.customerId,
        login: String = "budi01",
        password: String = "rahasia123",
        disabled: Boolean = false,
        indexed: Boolean = true,
    ) {
        TenantContext.runAs(tenantId) {
            val credential = PortalCredential.create(customerId, login, hasher.hash(password))
            if (disabled) credential.disable()
            credentials.save(credential)
            if (indexed) identitySync.sync(customerId)
        }
    }

    private fun login(identifier: String, password: String, tenantSlug: String? = null): PortalLoginResult =
        service.login(PortalLoginCommand(identifier, password, tenantSlug))

    private fun tokensOf(result: PortalLoginResult): PortalAuthTokens =
        (result as PortalLoginResult.Authenticated).tokens

    @Test
    fun `login benar menerbitkan token & profil serta mempublikasikan audit`() {
        seedCredential()

        val tokens = tokensOf(login("budi01", "rahasia123", "jayanet"))

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
    fun `login cukup dengan username tanpa menyebut ISP`() {
        seedCredential()

        val tokens = tokensOf(login("budi01", "rahasia123"))

        assertThat(tokens.customer.tenantSlug).isEqualTo("jayanet")
    }

    @Test
    fun `login pakai email apa adanya (beda huruf besar-kecil tetap dikenali)`() {
        seedCredential()

        val tokens = tokensOf(login("  BUDI@mail.com ", "rahasia123"))

        assertThat(tokens.customer.customerId).isEqualTo(customerId)
    }

    @Test
    fun `login pakai nomor HP yang ditulis dengan awalan nol`() {
        seedCredential()

        val tokens = tokensOf(login("0811-222-333", "rahasia123"))

        assertThat(tokens.customer.customerId).isEqualTo(customerId)
    }

    @Test
    fun `identitas kembar di dua ISP dengan password sama menawarkan pilihan ISP`() {
        seedCredential()
        seedCredential(tenantId = otherTenantId, customerId = otherCustomerId, login = "budi02")

        val result = login("budi@mail.com", "rahasia123")

        assertThat(result).isInstanceOf(PortalLoginResult.ChooseTenant::class.java)
        assertThat((result as PortalLoginResult.ChooseTenant).choices.map { it.tenantSlug })
            .containsExactlyInAnyOrder("jayanet", "sinarnet")
        // Belum masuk ke mana pun — tak ada token terbit, tak ada audit login.
        assertThat(auditEvents).isEmpty()
    }

    @Test
    fun `pilihan ISP diselesaikan dengan mengulang login memakai slug terpilih`() {
        seedCredential()
        seedCredential(tenantId = otherTenantId, customerId = otherCustomerId, login = "budi02")

        val tokens = tokensOf(login("budi@mail.com", "rahasia123", "sinarnet"))

        assertThat(tokens.customer.tenantSlug).isEqualTo("sinarnet")
    }

    @Test
    fun `identitas kembar tapi password hanya cocok di satu ISP langsung masuk tanpa bertanya`() {
        seedCredential()
        seedCredential(
            tenantId = otherTenantId, customerId = otherCustomerId, login = "budi02", password = "berbeda999",
        )

        val tokens = tokensOf(login("budi@mail.com", "rahasia123"))

        assertThat(tokens.customer.tenantSlug).isEqualTo("jayanet")
    }

    @Test
    fun `identitas tak dikenal gagal seragam`() {
        seedCredential()

        assertThatThrownBy { login("orang@lain.com", "rahasia123") }
            .isInstanceOf(AuthenticationException::class.java)
            .hasMessage("Login atau password salah")
    }

    @Test
    fun `login dengan password salah gagal seragam`() {
        seedCredential()

        assertThatThrownBy { login("budi01", "salah", "jayanet") }
            .isInstanceOf(AuthenticationException::class.java)
            .hasMessage("Login atau password salah")
    }

    @Test
    fun `login slug tak dikenal gagal seragam`() {
        seedCredential()

        assertThatThrownBy { login("budi01", "rahasia123", "tidakada") }
            .isInstanceOf(AuthenticationException::class.java)
            .hasMessage("Login atau password salah")
    }

    @Test
    fun `username masih bisa dipakai lewat slug walau belum terindeks`() {
        seedCredential(indexed = false)

        val tokens = tokensOf(login("budi01", "rahasia123", "jayanet"))

        assertThat(tokens.customer.customerId).isEqualTo(customerId)
    }

    @Test
    fun `login kredensial nonaktif ditolak`() {
        seedCredential(disabled = true)

        assertThatThrownBy { login("budi01", "rahasia123", "jayanet") }
            .isInstanceOf(AuthenticationException::class.java)
            .hasMessage("Akun portal dinonaktifkan")
    }

    @Test
    fun `login tenant suspended ditolak`() {
        tenants.replace(TenantRef(tenantId, "jayanet", "Jaya Net", TenantStatus.SUSPENDED))
        seedCredential()

        assertThatThrownBy { login("budi01", "rahasia123", "jayanet") }
            .isInstanceOf(AuthenticationException::class.java)
    }

    @Test
    fun `refresh merotasi token lama dan menerbitkan baru`() {
        seedCredential()
        val first = tokensOf(login("budi01", "rahasia123", "jayanet"))
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
        val tokens = tokensOf(login("budi01", "rahasia123", "jayanet"))

        service.logout(tokens.refreshToken)

        assertThatThrownBy { service.refresh(tokens.refreshToken) }
            .isInstanceOf(AuthenticationException::class.java)
    }

    @Test
    fun `logout token asing aman (no-op)`() {
        service.logout("token-tak-terdaftar")
    }
}
