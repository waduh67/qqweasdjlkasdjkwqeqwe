package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.network.domain.model.Odc
import java.util.UUID

interface OdcRepository {

    fun save(odc: Odc): Odc

    fun findById(id: UUID): Odc?

    fun findAllByIds(ids: Set<UUID>): List<Odc>

    fun search(query: String, areaIds: Set<UUID>?, pageRequest: PageRequest): Page<Odc>

    fun existsByCode(code: String): Boolean

    fun countByPonPortId(ponPortId: UUID): Long

    /** Jumlah ODC per PON port dalam satu query — menghindari N+1 di daftar PON port. */
    fun countByPonPortIds(ponPortIds: Set<UUID>): Map<UUID, Long>

    /** Id ODC yang menggantung pada salah satu PON port tersebut. */
    fun findIdsByPonPortIds(ponPortIds: Set<UUID>): Set<UUID>

    /** ODC yang menggantung pada sebuah PON port, urut kode — untuk drill-down PON → ODC → ODP. */
    fun findByPonPortId(ponPortId: UUID): List<Odc>

    fun deleteById(id: UUID)
}
