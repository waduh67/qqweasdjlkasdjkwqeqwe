package com.duluin.ftth.iam.application.port.outbound

import com.duluin.ftth.iam.domain.model.Area
import java.util.UUID

interface AreaRepository {

    fun save(area: Area): Area

    fun findById(id: UUID): Area?

    fun findAll(): List<Area>

    fun findAllByIds(ids: Set<UUID>): List<Area>

    fun existsByCode(code: String): Boolean

    fun deleteById(id: UUID)
}
