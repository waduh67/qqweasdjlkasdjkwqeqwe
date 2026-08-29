package com.duluin.ftth.hotspot.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.hotspot.application.port.outbound.HotspotSiteRepository
import com.duluin.ftth.hotspot.domain.model.HotspotSite
import com.duluin.ftth.hotspot.domain.model.HotspotSiteBranding
import com.duluin.ftth.hotspot.domain.model.PortalMode
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class HotspotSitePersistenceAdapter(
    private val jpa: HotspotSiteJpaRepository,
) : HotspotSiteRepository {
    override fun save(site: HotspotSite): HotspotSite {
        val entity = jpa.findById(site.id).orElse(null)?.apply {
            name = site.name
            location = site.location
            portalMode = site.portalMode.name
            brandingDisplayName = site.branding.displayName
            brandingLogoUrl = site.branding.logoUrl
            defaultPlanId = site.defaultPlanId
        } ?: HotspotSiteJpaEntity(
            id = site.id,
            nasId = site.nasId,
            portalId = site.portalId,
            name = site.name,
            location = site.location,
            portalMode = site.portalMode.name,
            brandingDisplayName = site.branding.displayName,
            brandingLogoUrl = site.branding.logoUrl,
            defaultPlanId = site.defaultPlanId,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findAll(): List<HotspotSite> = jpa.findAll().map { it.toDomain() }

    override fun findById(id: UUID): HotspotSite? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByNasId(nasId: UUID): HotspotSite? = jpa.findByNasId(nasId)?.toDomain()

    override fun findByPortalId(portalId: String): HotspotSite? = jpa.findByPortalId(portalId)?.toDomain()

    override fun findPublicByPortalId(portalId: String): HotspotSite? = jpa.findPublicByPortalId(portalId)?.toDomain()

    private fun HotspotSiteJpaEntity.toDomain() = HotspotSite.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        nasId = nasId,
        portalId = portalId,
        name = name,
        location = location,
        portalMode = PortalMode.valueOf(portalMode),
        branding = HotspotSiteBranding(brandingDisplayName, brandingLogoUrl),
        defaultPlanId = defaultPlanId,
    )
}
