package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.network.domain.model.PonPort
import java.util.UUID

interface PonPortRepository {

    fun save(ponPort: PonPort): PonPort

    fun findById(id: UUID): PonPort?

    fun findAllByIds(ids: Set<UUID>): List<PonPort>

    fun findByOltId(oltId: UUID): List<PonPort>

    fun existsByOltIdAndLabel(oltId: UUID, label: String): Boolean

    /** Jumlah PON port per OLT dalam satu query — menghindari N+1 di daftar OLT. */
    fun countByOltIds(oltIds: Set<UUID>): Map<UUID, Long>

    fun deleteById(id: UUID)
}
