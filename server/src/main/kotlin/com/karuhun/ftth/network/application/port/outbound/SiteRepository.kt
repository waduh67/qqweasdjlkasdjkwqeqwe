package com.karuhun.ftth.network.application.port.outbound

import com.karuhun.ftth.common.domain.Page
import com.karuhun.ftth.common.domain.PageRequest
import com.karuhun.ftth.network.domain.model.Site
import java.util.UUID

interface SiteRepository {

    fun save(site: Site): Site

    fun findById(id: UUID): Site?

    fun findAllByIds(ids: Set<UUID>): List<Site>

    fun search(query: String, pageRequest: PageRequest): Page<Site>

    fun existsByCode(code: String): Boolean

    fun deleteById(id: UUID)
}
