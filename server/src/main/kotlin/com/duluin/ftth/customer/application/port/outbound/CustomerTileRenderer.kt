package com.duluin.ftth.customer.application.port.outbound

import java.util.UUID

/**
 * Merender titik pelanggan menjadi layer Mapbox Vector Tile lewat `ST_AsMVT`.
 * Lihat `NetworkTileRenderer` untuk alasan pekerjaan ini dilakukan di database.
 */
interface CustomerTileRenderer {

    /** @param areaIds `null` = tanpa batas area; set kosong = tidak ada yang boleh dilihat. */
    fun render(z: Int, x: Int, y: Int, areaIds: Set<UUID>?): ByteArray
}
