package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.vo.SplitterRatio
import java.util.UUID

/**
 * Optical Distribution Cabinet — kabinet splitter tingkat pertama. Menerima satu
 * feeder dari sebuah PON port dan memecahnya ke sejumlah ODP.
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
    splitterRatio: SplitterRatio,
    capacity: Int,
    status: AssetStatus,
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

    var splitterRatio: SplitterRatio = splitterRatio
        private set

    var capacity: Int = capacity
        private set

    var status: AssetStatus = status
        private set

    fun update(
        name: String,
        address: String?,
        location: Coordinate,
        areaId: UUID?,
        splitterRatio: SplitterRatio,
        capacity: Int,
        status: AssetStatus,
    ) {
        this.name = AssetNaming.name(name, "ODC")
        this.address = AssetNaming.address(address)
        this.location = location
        this.areaId = areaId
        this.splitterRatio = splitterRatio
        this.capacity = validateCapacity(capacity)
        this.status = status
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

        fun create(
            tenantId: UUID,
            code: String,
            name: String,
            address: String?,
            location: Coordinate,
            areaId: UUID?,
            ponPortId: UUID?,
            splitterRatio: SplitterRatio,
            capacity: Int,
            status: AssetStatus = AssetStatus.ACTIVE,
        ): Odc = Odc(
            id = UuidV7.generate(),
            tenantId = tenantId,
            code = AssetNaming.code(code, "ODC"),
            name = AssetNaming.name(name, "ODC"),
            address = AssetNaming.address(address),
            location = location,
            areaId = areaId,
            ponPortId = ponPortId,
            splitterRatio = splitterRatio,
            capacity = validateCapacity(capacity),
            status = status,
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
            splitterRatio: SplitterRatio,
            capacity: Int,
            status: AssetStatus,
        ): Odc = Odc(id, tenantId, code, name, address, location, areaId, ponPortId, splitterRatio, capacity, status)

        private fun validateCapacity(capacity: Int): Int {
            if (capacity !in 1..MAX_CAPACITY) {
                throw ValidationException("Kapasitas ODC harus 1-$MAX_CAPACITY")
            }
            return capacity
        }
    }
}
