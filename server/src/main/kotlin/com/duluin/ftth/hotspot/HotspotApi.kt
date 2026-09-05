package com.duluin.ftth.hotspot

import com.duluin.ftth.hotspot.domain.model.HotspotSite
import java.util.UUID

interface HotspotApi {
    fun findSite(siteId: UUID): HotspotSite?
    fun findProvisioningSite(siteId: UUID): HotspotSiteRef? = findSite(siteId)?.let {
        HotspotSiteRef(it.id, it.tenantId, it.nasId, it.defaultPlanId, it.portalMode.name)
    }
}

data class HotspotSiteRef(
    val id: UUID,
    val tenantId: UUID,
    val nasId: UUID,
    val defaultPlanId: UUID?,
    val portalMode: String,
)
