package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.AssetStatus
import java.util.UUID

interface ManageOdcUseCase {

    fun search(query: String, pageRequest: PageRequest): Page<OdcView>

    fun get(id: UUID): OdcView

    fun create(command: SaveOdcCommand): OdcView

    fun update(id: UUID, command: SaveOdcCommand): OdcView

    /** Menyambung/melepas feeder ODC ke PON port; `ponPortId` null = melepas. */
    fun connect(id: UUID, ponPortId: UUID?): OdcView

    fun delete(id: UUID)
}

data class SaveOdcCommand(
    val code: String,
    val name: String,
    val address: String?,
    val location: Coordinate,
    val areaId: UUID?,
    val ponPortId: UUID?,
    val splitterRatio: String,
    val capacity: Int,
    val status: AssetStatus,
)
