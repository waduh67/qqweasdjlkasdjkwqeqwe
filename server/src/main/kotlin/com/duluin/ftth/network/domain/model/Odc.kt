package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import java.time.LocalDate
import java.util.UUID

/**
 * Optical Distribution Cabinet — kabinet distribusi tingkat pertama. Menerima
 * feeder dari PON port dan meneruskannya ke sejumlah ODP.
 *
 * Splitter di dalamnya BUKAN atribut kabinet melainkan benda tersendiri (lihat
 * [Splitter]): satu kabinet berisi beberapa modul dengan rasio berbeda, dan ada
 * pula yang murni cross-connect tanpa splitter sama sekali.
 */
class Odc private constructor(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    name: String,
    address: String?,
    location: Coordinate,
    areaId: UUID?,
    ponPortId: UUID?,
    capacity: Int,
    status: AssetStatus,
    installedOn: LocalDate?,
    mounting: MountingType?,
    notes: String?,
) {
    var name: String = name
        private set

    var address: String? = address
        private set

    var location: Coordinate = location
        private set

    var areaId: UUID? = areaId
        private set

    var ponPortId: UUID? = ponPortId
        private set

    var capacity: Int = capacity
        private set

    var status: AssetStatus = status
        private set

    /** Tanggal pemasangan — umur aset, dasar jadwal preventif & klaim garansi. */
    var installedOn: LocalDate? = installedOn
        private set

    /** Dudukan kabinet; menentukan alat yang dibawa teknisi. Lihat [MountingType]. */
    var mounting: MountingType? = mounting
        private set

    /** Pesan teknisi untuk teknisi berikutnya — "kunci di pos satpam", dst. */
    var notes: String? = notes
        private set

    @Suppress("LongParameterList")
    fun update(
        name: String,
        address: String?,
        location: Coordinate,
        areaId: UUID?,
        capacity: Int,
        status: AssetStatus,
        installedOn: LocalDate?,
        mounting: MountingType?,
        notes: String?,
    ) {
        this.name = AssetNaming.name(name, "ODC")
        this.address = AssetNaming.address(address)
        this.location = location
        this.areaId = areaId
        this.capacity = validateCapacity(capacity)
        this.status = status
        this.installedOn = installedOn
        this.mounting = mounting
        this.notes = AssetNaming.notes(notes)
    }

    /** Menyambungkan ODC ke feeder; `null` melepaskannya (mis. saat migrasi OLT). */
    fun connectTo(ponPortId: UUID?) {
        this.ponPortId = ponPortId
    }

    /** Memindah titik ODC di peta tanpa menyentuh atribut lain. */
    fun relocate(location: Coordinate) {
        this.location = location
    }

    /** ODC tanpa uplink tidak bisa mengalirkan layanan meski statusnya aktif. */
    fun isEnergized(): Boolean = status.acceptsService() && ponPortId != null

    companion object {
        const val MAX_CAPACITY = 1_024

        @Suppress("LongParameterList")
        fun create(
            tenantId: UUID,
            code: String,
            name: String,
            address: String?,
            location: Coordinate,
            areaId: UUID?,
            ponPortId: UUID?,
            capacity: Int,
            status: AssetStatus = AssetStatus.ACTIVE,
            installedOn: LocalDate? = null,
            mounting: MountingType? = null,
            notes: String? = null,
        ): Odc = Odc(
            id = UuidV7.generate(),
            tenantId = tenantId,
            code = AssetNaming.code(code, "ODC"),
            name = AssetNaming.name(name, "ODC"),
            address = AssetNaming.address(address),
            location = location,
            areaId = areaId,
            ponPortId = ponPortId,
            capacity = validateCapacity(capacity),
            status = status,
            installedOn = installedOn,
            mounting = mounting,
            notes = AssetNaming.notes(notes),
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            code: String,
            name: String,
            address: String?,
            location: Coordinate,
            areaId: UUID?,
            ponPortId: UUID?,
            capacity: Int,
            status: AssetStatus,
            installedOn: LocalDate?,
            mounting: MountingType?,
            notes: String?,
        ): Odc = Odc(
            id, tenantId, code, name, address, location, areaId, ponPortId, capacity, status,
            installedOn, mounting, notes,
        )

        private fun validateCapacity(capacity: Int): Int {
            if (capacity !in 1..MAX_CAPACITY) {
                throw ValidationException("Kapasitas ODC harus 1-$MAX_CAPACITY")
            }
            return capacity
        }
    }
}
