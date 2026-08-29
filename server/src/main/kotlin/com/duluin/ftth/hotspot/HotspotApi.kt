package com.duluin.ftth.hotspot

import com.duluin.ftth.hotspot.domain.model.HotspotSite
import java.util.UUID

interface HotspotApi {
    fun findSite(siteId: UUID): HotspotSite?
}
