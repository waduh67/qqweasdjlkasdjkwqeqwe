package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.AssetStatus
import java.util.UUID

interface ManageOdpUseCase {

    fun search(query: String, odcId: UUID?, pageRequest: PageRequest): Page<OdpView>

    fun get(id: UUID): OdpView

    fun create(command: SaveOdpCommand): OdpView

    fun update(id: UUID, command: SaveOdpCommand): OdpView

    /** Memindah titik ODP di peta; ujung kabel yang menyentuhnya ikut menempel ulang. */
    fun relocate(id: UUID, location: Coordinate): OdpView

    fun connect(id: UUID, odcId: UUID?): OdpView

    fun delete(id: UUID)
}

data class SaveOdpCommand(
    val code: String,
    val name: String,
    val address: String?,
    val location: Coordinate,
    val areaId: UUID?,
    val odcId: UUID?,
    val splitterRatio: String,
    val capacity: Int,
    val status: AssetStatus,
)
