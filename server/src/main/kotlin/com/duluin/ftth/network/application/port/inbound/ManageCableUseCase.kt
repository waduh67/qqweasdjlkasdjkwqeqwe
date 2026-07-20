package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.AssetStatus
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import java.util.UUID

interface ManageCableUseCase {

    fun search(query: String, cableType: CableType?, pageRequest: PageRequest): Page<CableView>

    fun get(id: UUID): CableView

    fun create(command: SaveCableCommand): CableView

    fun update(id: UUID, command: SaveCableCommand): CableView

    fun delete(id: UUID)
}

data class SaveCableCommand(
    val code: String,
    val name: String,
    val cableType: CableType,
    val coreCount: Int,
    val route: List<Coordinate>,
    val fromKind: NetworkNodeKind,
    val fromId: UUID,
    val toKind: NetworkNodeKind,
    val toId: UUID,
    val status: AssetStatus,
)
