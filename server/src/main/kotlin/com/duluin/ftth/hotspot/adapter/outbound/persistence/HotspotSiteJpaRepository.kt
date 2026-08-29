package com.duluin.ftth.hotspot.adapter.outbound.persistence

import com.duluin.ftth.hotspot.domain.model.HotspotSite
import com.duluin.ftth.hotspot.domain.model.HotspotSiteBranding
import com.duluin.ftth.hotspot.domain.model.PortalMode
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HotspotSiteJpaRepository : JpaRepository<HotspotSiteJpaEntity, UUID> {
    fun findByNasId(nasId: UUID): HotspotSiteJpaEntity?
    fun findByPortalId(portalId: String): HotspotSiteJpaEntity?

    @org.springframework.data.jpa.repository.Query(
        value = """
            SELECT portal_id AS "portalId", tenant_id AS "tenantId", site_id AS "siteId",
                   nas_id AS "nasId", name, portal_mode AS "portalMode",
                   branding_display_name AS "brandingDisplayName", branding_logo_url AS "brandingLogoUrl"
            FROM hotspot_public_portal_index
            WHERE portal_id = :portalId
        """,
        nativeQuery = true,
    )
    fun findPublicByPortalId(portalId: String): PublicHotspotSiteRow?

    interface PublicHotspotSiteRow {
        val portalId: String
        val tenantId: UUID
        val siteId: UUID
        val nasId: UUID
        val name: String
        val portalMode: String
        val brandingDisplayName: String?
        val brandingLogoUrl: String?

        fun toDomain(): HotspotSite = HotspotSite.rehydrate(
            id = siteId,
            tenantId = tenantId,
            nasId = nasId,
            portalId = portalId,
            name = name,
            location = null,
            portalMode = PortalMode.valueOf(portalMode),
            branding = HotspotSiteBranding(brandingDisplayName, brandingLogoUrl),
            defaultPlanId = null,
        )
    }
}
