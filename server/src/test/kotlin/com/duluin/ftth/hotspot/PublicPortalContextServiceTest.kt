package com.duluin.ftth.hotspot

import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.hotspot.application.port.inbound.InvalidPortalContextException
import com.duluin.ftth.hotspot.application.port.inbound.IssuePortalContextCommand
import com.duluin.ftth.hotspot.application.port.outbound.HotspotSiteRepository
import com.duluin.ftth.hotspot.application.service.PublicPortalContextService
import com.duluin.ftth.hotspot.domain.model.HotspotSite
import com.duluin.ftth.hotspot.domain.model.HotspotSiteBranding
import com.duluin.ftth.hotspot.domain.model.PortalMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PublicPortalContextServiceTest {
    private val tenantId = UUID.randomUUID()
    private val now = Instant.now()
    private val site = HotspotSite.rehydrate(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        nasId = UUID.randomUUID(),
        portalId = "aBcDeFgHiJkLmNoPqRsTuV",
        name = "Lobby",
        location = null,
        portalMode = PortalMode.NETOPS_HOSTED,
        branding = HotspotSiteBranding("Guest Wi-Fi", "https://cdn.example.test/logo.svg"),
        defaultPlanId = null,
    )

    @Test
    fun `accepts the signed MikroTik external-portal redirect fixture`() {
        val service = service(site)
        val issued = service.issue(
            IssuePortalContextCommand(
                portalId = site.portalId,
                clientMac = "AA:BB:CC:DD:EE:FF",
                clientIp = "192.0.2.10",
                originalUrl = "https://login.example.test/login?dst=https%3A%2F%2Finternet.example%2F",
            ),
        )

        val resolved = TenantContext.runAs(tenantId) { service.resolve(issued.state) }

        assertThat(resolved.displayName).isEqualTo("Guest Wi-Fi")
        assertThat(resolved.logoUrl).isEqualTo("https://cdn.example.test/logo.svg")
        assertThat(resolved.redirectUrl).isEqualTo("https://login.example.test/login?dst=https%3A%2F%2Finternet.example%2F")
        assertThat(resolved.clientMac).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(resolved.clientIp).isEqualTo("192.0.2.10")
    }

    @Test
    fun `signed context can be replayed without changing its resolved portal data`() {
        val issued = service(site).issue(
            IssuePortalContextCommand(site.portalId, clientMac = "AA:BB:CC:DD:EE:FF", clientIp = "192.0.2.10"),
        )

        val first = service(site).resolve(issued.state)
        val replay = service(site).resolve(issued.state)

        assertThat(replay).isEqualTo(first)
    }

    @Test
    fun `rejects tampered and expired signed context`() {
        val issued = service(site, now.minus(Duration.ofHours(1))).issue(IssuePortalContextCommand(site.portalId))

        assertThatThrownBy { service(site).resolve("${issued.state}x") }
            .isInstanceOf(InvalidPortalContextException::class.java)
        assertThatThrownBy { service(site).resolve(issued.state) }
            .isInstanceOf(InvalidPortalContextException::class.java)
    }

    @Test
    fun `replaying a signed context is safe and does not alter its binding or expiry`() {
        val service = service(site)
        val issued = service.issue(
            IssuePortalContextCommand(site.portalId, "AA:BB:CC:DD:EE:FF", "192.0.2.10"),
        )

        val first = service.resolve(issued.state)
        val replay = service.resolve(issued.state)

        assertThat(replay).isEqualTo(first)
        assertThat(replay.clientMac).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(replay.clientIp).isEqualTo("192.0.2.10")
    }

    @Test
    fun `rejects stale context when its hosted site no longer accepts public portal traffic`() {
        val issued = service(site).issue(IssuePortalContextCommand(site.portalId))
        val disabledSite = HotspotSite.rehydrate(
            id = site.id, tenantId = site.tenantId, nasId = site.nasId, portalId = site.portalId,
            name = site.name, location = site.location, portalMode = PortalMode.OFF,
            branding = site.branding, defaultPlanId = site.defaultPlanId,
        )

        assertThatThrownBy { service(disabledSite).resolve(issued.state) }
            .isInstanceOf(InvalidPortalContextException::class.java)
    }

    @Test
    fun `rejects unsafe redirect and non-hosted context`() {
        val service = service(site)

        assertThatThrownBy {
            service.issue(IssuePortalContextCommand(site.portalId, originalUrl = "https://attacker.example/"))
        }.isInstanceOf(InvalidPortalContextException::class.java)
        assertThatThrownBy {
            service.issue(IssuePortalContextCommand(site.portalId, originalUrl = "https://login.example.test@attacker.example/"))
        }.isInstanceOf(InvalidPortalContextException::class.java)
        assertThatThrownBy {
            service.issue(IssuePortalContextCommand(site.portalId, originalUrl = "https://login.example.test/#attacker"))
        }.isInstanceOf(InvalidPortalContextException::class.java)

        val nonHosted = HotspotSite.rehydrate(
            id = UUID.randomUUID(), tenantId = tenantId, nasId = UUID.randomUUID(), portalId = "zYxWvUtSrQpOnMlKjIhGfE",
            name = "NAS", location = null, portalMode = PortalMode.NAS_OWNED,
            branding = HotspotSiteBranding(null, null), defaultPlanId = null,
        )
        assertThatThrownBy { service(nonHosted).issue(IssuePortalContextCommand(nonHosted.portalId)) }
            .isInstanceOf(InvalidPortalContextException::class.java)
    }

    private fun service(site: HotspotSite, issuedAt: Instant = now): PublicPortalContextService = PublicPortalContextService(
        sites = Repository(site),
        properties = PortalContextProperties(Duration.ofMinutes(5), setOf("login.example.test")),
        securityProperties = SecurityProperties(
            jwtSecret = "test-only-secret-ftth-oss-1234567890-abcdef",
            encryptionSecret = "test-only-encryption-ftth-oss-0987654321-fedcba",
        ),
        audit = AuditRecorder(ApplicationEventPublisher { }, NoCurrentUser),
        clock = Clock.fixed(issuedAt, ZoneOffset.UTC),
    )

    private class Repository(private val site: HotspotSite) : HotspotSiteRepository {
        override fun save(site: HotspotSite): HotspotSite = site
        override fun findAll(): List<HotspotSite> = listOf(site)
        override fun findById(id: UUID): HotspotSite? = if (id == site.id && TenantContext.tenantIdOrNull() == site.tenantId) site else null
        override fun findByNasId(nasId: UUID): HotspotSite? = if (nasId == site.nasId) site else null
        override fun findByPortalId(portalId: String): HotspotSite? = if (portalId == site.portalId) site else null
        override fun findPublicByPortalId(portalId: String): HotspotSite? = findByPortalId(portalId)
    }

    private object NoCurrentUser : CurrentUserProvider {
        override fun currentOrNull() = null
    }
}
