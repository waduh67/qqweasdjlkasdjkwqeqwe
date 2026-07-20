package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.network.domain.model.Olt
import java.util.UUID

interface OltRepository {

    fun save(olt: Olt): Olt

    fun findById(id: UUID): Olt?

    fun findAllByIds(ids: Set<UUID>): List<Olt>

    fun search(query: String, siteId: UUID?, pageRequest: PageRequest): Page<Olt>

    fun existsByCode(code: String): Boolean

    fun countBySiteId(siteId: UUID): Long

    /**
     * Jumlah OLT per site dalam satu query. Ada demi menghindari N+1 saat
     * merender daftar site; site tanpa OLT tidak muncul di hasil.
     */
    fun countBySiteIds(siteIds: Set<UUID>): Map<UUID, Long>

    fun deleteById(id: UUID)
}
