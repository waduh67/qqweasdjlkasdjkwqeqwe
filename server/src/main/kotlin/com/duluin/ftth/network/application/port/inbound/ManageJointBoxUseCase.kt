package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.AssetStatus
import java.util.UUID

/**
 * Joint box sengaja tak punya `connect`: ia tak punya uplink logis. Kotak sambung
 * bukan "milik" ODC mana pun — yang menentukan ia melayani jalur apa adalah serat
 * yang disambung di dalamnya, dan itu dicatat lewat sambungan (potongan B).
 */
interface ManageJointBoxUseCase {

    fun search(query: String, pageRequest: PageRequest): Page<JointBoxView>

    fun get(id: UUID): JointBoxView

    fun create(command: SaveJointBoxCommand): JointBoxView

    fun update(id: UUID, command: SaveJointBoxCommand): JointBoxView

    /** Memindah titik JB di peta; ujung kabel yang menyentuhnya ikut menempel ulang. */
    fun relocate(id: UUID, location: Coordinate): JointBoxView

    fun delete(id: UUID)
}

data class SaveJointBoxCommand(
    val code: String,
    val name: String,
    val address: String?,
    val location: Coordinate,
    val areaId: UUID?,
    val trayCount: Int,
    val capacity: Int,
    val status: AssetStatus,
)
