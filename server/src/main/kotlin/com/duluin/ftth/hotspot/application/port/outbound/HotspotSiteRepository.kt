package com.duluin.ftth.hotspot.application.port.outbound

import com.duluin.ftth.hotspot.domain.model.HotspotSite
import java.util.UUID

interface HotspotSiteRepository {
    fun save(site: HotspotSite): HotspotSite
    fun findAll(): List<HotspotSite>
    fun findById(id: UUID): HotspotSite?
    fun findByNasId(nasId: UUID): HotspotSite?
    fun findByPortalId(portalId: String): HotspotSite?
    fun findPublicByPortalId(portalId: String): HotspotSite? = null
}
