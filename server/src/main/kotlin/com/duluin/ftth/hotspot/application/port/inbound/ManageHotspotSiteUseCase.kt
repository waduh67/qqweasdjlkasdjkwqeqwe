package com.duluin.ftth.hotspot.application.port.inbound

import com.duluin.ftth.hotspot.domain.model.HotspotSite
import com.duluin.ftth.hotspot.domain.model.HotspotSiteBranding
import com.duluin.ftth.hotspot.domain.model.PortalMode
import java.util.UUID

data class CreateHotspotSiteCommand(
    val nasId: UUID,
    val name: String,
    val location: String? = null,
    val portalMode: PortalMode,
    val branding: HotspotSiteBranding = HotspotSiteBranding(null, null),
    val defaultPlanId: UUID? = null,
)

data class UpdateHotspotSiteCommand(
    val name: String,
    val location: String? = null,
    val portalMode: PortalMode,
    val branding: HotspotSiteBranding = HotspotSiteBranding(null, null),
    val defaultPlanId: UUID? = null,
)

interface ManageHotspotSiteUseCase {
    fun list(): List<HotspotSite>
    fun get(siteId: UUID): HotspotSite
    fun create(command: CreateHotspotSiteCommand): HotspotSite
    fun update(siteId: UUID, command: UpdateHotspotSiteCommand): HotspotSite
}
