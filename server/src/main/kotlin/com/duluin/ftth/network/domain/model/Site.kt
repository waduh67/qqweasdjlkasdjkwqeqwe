package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.geo.Coordinate
import java.util.UUID

/**
 * Site / POP: lokasi fisik tempat OLT berada (kantor, shelter, gedung).
 * Akar dari rantai OLT → PON port → ODC → ODP → pelanggan.
 */
class Site private constructor(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    name: String,
    address: String?,
    location: Coordinate,
    areaId: UUID?,
) {
    var name: String = name
        private set

    var address: String? = address
        private set

    var location: Coordinate = location
        private set

    var areaId: UUID? = areaId
        private set

    fun update(name: String, address: String?, location: Coordinate, areaId: UUID?) {
        this.name = AssetNaming.name(name, "site")
        this.address = AssetNaming.address(address)
        this.location = location
        this.areaId = areaId
    }

    companion object {
        fun create(
            tenantId: UUID,
            code: String,
            name: String,
            address: String?,
            location: Coordinate,
            areaId: UUID?,
        ): Site = Site(
            id = UuidV7.generate(),
            tenantId = tenantId,
            code = AssetNaming.code(code, "site"),
            name = AssetNaming.name(name, "site"),
            address = AssetNaming.address(address),
            location = location,
            areaId = areaId,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            code: String,
            name: String,
            address: String?,
            location: Coordinate,
            areaId: UUID?,
        ): Site = Site(id, tenantId, code, name, address, location, areaId)
    }
}
