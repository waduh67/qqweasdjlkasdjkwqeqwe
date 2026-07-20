package com.duluin.ftth.network.application.port.outbound

import java.util.UUID

/**
 * Merender aset jaringan menjadi Mapbox Vector Tile.
 *
 * Ada sebagai port tersendiri karena implementasinya SQL PostGIS murni
 * (`ST_AsMVT`) — pekerjaan yang harus dilakukan database, bukan JVM. Menarik
 * puluhan ribu geometri ke memori hanya untuk diserialkan ulang adalah cara
 * paling pasti membuat peta melambat begitu jaringan bertumbuh.
 */
interface NetworkTileRenderer {

    /**
     * @param areaIds `null` = tanpa batas area; set kosong = tidak ada yang boleh dilihat.
     * @return tile MVT berisi layer `site`, `odc`, `odp`, dan `cable`.
     */
    fun render(z: Int, x: Int, y: Int, areaIds: Set<UUID>?): ByteArray
}
