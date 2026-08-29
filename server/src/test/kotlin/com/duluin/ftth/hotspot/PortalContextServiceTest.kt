package com.duluin.ftth.hotspot

import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.hotspot.application.port.outbound.HotspotSiteRepository
import com.duluin.ftth.hotspot.application.service.InvalidPortalContextException
import com.duluin.ftth.hotspot.application.service.PortalContextService
import com.duluin.ftth.hotspot.domain.model.HotspotSite
import com.duluin.ftth.hotspot.domain.model.HotspotSiteBranding
import com.duluin.ftth.hotspot.domain.model.PortalMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PortalContextServiceTest {
    private val now = Instant.parse("2026-08-29T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val site = HotspotSite.rehydrate(
        id = UUID.randomUUID(),
        tenantId = UUID.randomUUID(),
        nasId = UUID.randomUUID(),
        portalId = "PortalContextTest_1234",
        name = "Lobby",
        location = null,
        portalMode = PortalMode.NETOPS_HOSTED,
        branding = HotspotSiteBranding("Guest Wi-Fi", "https://cdn.example/logo.svg"),
        defaultPlanId = null,
    )

    @Test
    fun `issues and resolves tenant site NAS bound state`() {
        val service = service(site)

        val issued = service.issue(site.portalId, "/welcome")
        val context = service.resolve(site.portalId, issued.state)

        assertThat(issued.expiresAt).isEqualTo(now.plusSeconds(300))
        assertThat(context.siteName).isEqualTo("Lobby")
        assertThat(context.displayName).isEqualTo("Guest Wi-Fi")
        assertThat(context.returnPath).isEqualTo("/welcome")
    }

    @Test
    fun `rejects tampered expired wrong mode and unsafe states`() {
        val hosted = service(site)
        val state = hosted.issue(site.portalId, null).state
        assertThatThrownBy { hosted.resolve(site.portalId, "${state}x") }
            .isInstanceOf(InvalidPortalContextException::class.java)
        assertThatThrownBy { hosted.issue(site.portalId, "https://attacker.example") }
            .isInstanceOf(InvalidPortalContextException::class.java)
        assertThatThrownBy { hosted.issue(site.portalId, "//attacker.example") }
            .isInstanceOf(InvalidPortalContextException::class.java)

        val expired = PortalContextService(repository(site), properties(), Clock.fixed(now.plusSeconds(301), ZoneOffset.UTC))
        assertThatThrownBy { expired.resolve(site.portalId, state) }
            .isInstanceOf(InvalidPortalContextException::class.java)

        val nonHosted = HotspotSite.rehydrate(
            site.id, site.tenantId, site.nasId, site.portalId, site.name, site.location,
            PortalMode.NAS_OWNED, site.branding, site.defaultPlanId,
        )
        assertThatThrownBy { service(nonHosted).issue(nonHosted.portalId, null) }
            .isInstanceOf(InvalidPortalContextException::class.java)
    }

    private fun service(site: HotspotSite): PortalContextService = PortalContextService(repository(site), properties(), clock)

    private fun repository(site: HotspotSite) = object : HotspotSiteRepository {
        override fun save(site: HotspotSite) = site
        override fun findAll() = listOf(site)
        override fun findById(id: UUID) = site.takeIf { it.id == id }
        override fun findByNasId(nasId: UUID) = site.takeIf { it.nasId == nasId }
        override fun findByPortalId(portalId: String) = site.takeIf { it.portalId == portalId }
    }

    private fun properties() = SecurityProperties(
        jwtSecret = "a".repeat(32),
        encryptionSecret = "b".repeat(32),
        portalJwtSecret = "c".repeat(32),
    )
}
