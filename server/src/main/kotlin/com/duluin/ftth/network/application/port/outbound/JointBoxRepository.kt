package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.network.domain.model.JointBox
import java.util.UUID

interface JointBoxRepository {

    fun save(jointBox: JointBox): JointBox

    fun findById(id: UUID): JointBox?

    /**
     * @param areaIds `null` berarti tanpa pembatasan area; set kosong berarti
     *        pengguna tidak punya area sama sekali sehingga hasilnya kosong.
     */
    fun search(query: String, areaIds: Set<UUID>?, pageRequest: PageRequest): Page<JointBox>

    fun existsByCode(code: String): Boolean

    fun deleteById(id: UUID)
}
