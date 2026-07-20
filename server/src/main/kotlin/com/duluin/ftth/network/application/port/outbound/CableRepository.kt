package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import java.util.UUID

interface CableRepository {

    fun save(cable: Cable): Cable

    fun findById(id: UUID): Cable?

    fun search(query: String, cableType: CableType?, pageRequest: PageRequest): Page<Cable>

    /** Kabel yang menyentuh simpul tertentu di salah satu ujungnya. */
    fun findByEndpoint(node: NetworkNodeRef): List<Cable>

    fun existsByCode(code: String): Boolean

    fun deleteById(id: UUID)
}
