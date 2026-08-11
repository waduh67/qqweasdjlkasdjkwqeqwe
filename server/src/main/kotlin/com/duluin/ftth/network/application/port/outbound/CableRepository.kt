package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import java.util.UUID

interface CableRepository {

    fun save(cable: Cable): Cable

    fun findById(id: UUID): Cable?

    fun findByIds(ids: Collection<UUID>): List<Cable>

    fun search(query: String, cableType: CableType?, pageRequest: PageRequest): Page<Cable>

    /** Kabel yang menyentuh simpul tertentu di salah satu ujungnya. */
    fun findByEndpoint(node: NetworkNodeRef): List<Cable>

    /** Kabel yang salah satu ujung id-nya ada di [nodeIds] (abai jenis simpul). */
    fun findByEndpointNodeIds(nodeIds: Set<UUID>): List<Cable>

    /**
     * Kabel yang RUTENYA lewat maksimal [radiusMeters] dari [location] — termasuk
     * yang cuma dilewati, bukan berujung, di sana.
     *
     * Ada karena keanggotaan sebuah simpul pada sebuah kabel tak bisa disimpulkan
     * dari pasangan from/to-nya: ODP menempel di TENGAH bentang (mid-span
     * tapping). Dikerjakan di database supaya indeks GiST yang menyaring, bukan
     * seluruh kabel tenant dimuat ke memori lalu diukur satu per satu.
     */
    fun findPassing(location: Coordinate, radiusMeters: Double): List<Cable>

    fun existsByCode(code: String): Boolean

    fun deleteById(id: UUID)
}
