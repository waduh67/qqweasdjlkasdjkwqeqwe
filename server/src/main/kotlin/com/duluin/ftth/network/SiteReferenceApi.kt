package com.duluin.ftth.network

import com.duluin.ftth.common.security.AuthorityScope
import java.util.UUID

interface SiteReferenceApi {
    fun lock(id: UUID): SiteAreaReference?
    fun lockForChange(id: UUID): SiteAreaReference?
    fun visibleAreas(scope: AuthorityScope): Map<UUID, UUID?>
}

data class SiteAreaReference(val id: UUID, val areaId: UUID?)

interface SiteUsageProbe {
    fun assertSiteUnreferenced(siteId: UUID)
}
