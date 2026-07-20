package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.network.domain.model.Site
import java.util.UUID

interface SiteRepository {

    fun save(site: Site): Site

    fun findById(id: UUID): Site?

    fun findAllByIds(ids: Set<UUID>): List<Site>

    fun search(query: String, pageRequest: PageRequest): Page<Site>

    fun existsByCode(code: String): Boolean

    fun deleteById(id: UUID)
}
