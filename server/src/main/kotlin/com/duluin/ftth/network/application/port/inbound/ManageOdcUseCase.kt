package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.AssetStatus
import com.duluin.ftth.network.domain.model.MountingType
import java.time.LocalDate
import java.util.UUID

interface ManageOdcUseCase {

    fun search(query: String, pageRequest: PageRequest): Page<OdcView>

    fun get(id: UUID): OdcView

    fun create(command: SaveOdcCommand): OdcView

    fun update(id: UUID, command: SaveOdcCommand): OdcView

    /** Memindah titik ODC di peta; ujung kabel yang menyentuhnya ikut menempel ulang. */
    fun relocate(id: UUID, location: Coordinate): OdcView

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
    /**
     * Jalan pintas kabinet satu modul: mengisi/mengganti rasio splitter tunggalnya,
     * null/kosong = kabinet tanpa splitter. Kabinet berisi banyak modul tak tersentuh
     * bidang ini — modulnya diurus lewat [ManageSplitterUseCase].
     */
    val splitterRatio: String?,
    val capacity: Int,
    val status: AssetStatus,
    /**
     * Data lapangan; ketiganya boleh null karena aset lama tak pernah ditanyai —
     * menebaknya lebih buruk daripada mengaku belum tahu.
     */
    val installedOn: LocalDate? = null,
    val mounting: MountingType? = null,
    val notes: String? = null,
)
