package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.AssetStatus
import java.util.UUID

/**
 * ODF sengaja tak punya `connect` seperti ODC/ODP: ia tak berlangganan uplink.
 * Rak terminasi melayani jalur apa saja yang seratnya berhenti di situ, dan itu
 * ditentukan oleh sambungan di tiap portnya — bukan oleh satu induk tunggal.
 */
interface ManageOdfUseCase {

    fun search(query: String, pageRequest: PageRequest): Page<OdfView>

    fun get(id: UUID): OdfView

    fun create(command: SaveOdfCommand): OdfView

    fun update(id: UUID, command: SaveOdfCommand): OdfView

    /** Memindah titik ODF di peta; ujung kabel yang menyentuhnya ikut menempel ulang. */
    fun relocate(id: UUID, location: Coordinate): OdfView

    fun delete(id: UUID)
}

data class SaveOdfCommand(
    val code: String,
    val name: String,
    val siteId: UUID,
    val location: Coordinate,
    val areaId: UUID?,
    val portCount: Int,
    val status: AssetStatus,
)
