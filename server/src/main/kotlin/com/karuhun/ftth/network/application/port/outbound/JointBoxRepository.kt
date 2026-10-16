package com.karuhun.ftth.network.application.port.outbound

import com.karuhun.ftth.common.domain.Page
import com.karuhun.ftth.common.domain.PageRequest
import com.karuhun.ftth.network.domain.model.JointBox
import java.util.UUID

interface JointBoxRepository {

    fun save(jointBox: JointBox): JointBox

    fun findById(id: UUID): JointBox?

    /** Sekumpulan kotak dalam satu query — penawar N+1 di layar yang menyebut banyak kotak. */
    fun findAllByIds(ids: Set<UUID>): List<JointBox>

    /**
     * @param areaIds `null` berarti tanpa pembatasan area; set kosong berarti
     *        pengguna tidak punya area sama sekali sehingga hasilnya kosong.
     */
    fun search(query: String, areaIds: Set<UUID>?, pageRequest: PageRequest): Page<JointBox>

    fun existsByCode(code: String): Boolean

    fun deleteById(id: UUID)
}
