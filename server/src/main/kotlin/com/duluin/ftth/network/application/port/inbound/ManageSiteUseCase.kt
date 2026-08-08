package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import java.util.UUID

interface ManageSiteUseCase {

    fun search(query: String, pageRequest: PageRequest): Page<SiteView>

    fun get(id: UUID): SiteView

    fun create(command: SaveSiteCommand): SiteView

    fun update(id: UUID, command: SaveSiteCommand): SiteView

    /** Memindah titik site di peta; ujung kabel yang menyentuhnya ikut menempel ulang. */
    fun relocate(id: UUID, location: Coordinate): SiteView

    fun delete(id: UUID)
}

data class SaveSiteCommand(
    val code: String,
    val name: String,
    val address: String?,
    val location: Coordinate,
    val areaId: UUID?,
)
