package com.karuhun.ftth.iam.application.port.outbound

import com.karuhun.ftth.iam.domain.model.Area
import com.karuhun.ftth.common.domain.Page
import com.karuhun.ftth.common.domain.PageRequest
import com.karuhun.ftth.common.security.AuthorityScope
import java.util.UUID

interface AreaRepository {

    fun save(area: Area): Area

    fun findById(id: UUID): Area?

    fun findAll(): List<Area>

    fun findAllByIds(ids: Set<UUID>): List<Area>

    fun search(scope: AuthorityScope, query: String, page: PageRequest): Page<Area>

    fun existsByCode(code: String): Boolean

    fun deleteById(id: UUID)
}
