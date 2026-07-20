package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.network.domain.model.Odp
import java.util.UUID

interface OdpRepository {

    fun save(odp: Odp): Odp

    fun findById(id: UUID): Odp?

    fun findAllByIds(ids: Set<UUID>): List<Odp>

    /**
     * @param areaIds `null` berarti tanpa pembatasan area; set kosong berarti
     *        pengguna tidak punya area sama sekali sehingga hasilnya kosong.
     */
    fun search(query: String, areaIds: Set<UUID>?, odcId: UUID?, pageRequest: PageRequest): Page<Odp>

    fun findByOdcId(odcId: UUID): List<Odp>

    fun existsByCode(code: String): Boolean

    fun countByOdcId(odcId: UUID): Long

    /** Jumlah ODP per ODC dalam satu query — menghindari N+1 di daftar ODC. */
    fun countByOdcIds(odcIds: Set<UUID>): Map<UUID, Long>

    fun deleteById(id: UUID)
}
