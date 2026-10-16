package com.karuhun.ftth.tenancy.application.port.outbound

import com.karuhun.ftth.common.domain.Page
import com.karuhun.ftth.common.domain.PageRequest
import com.karuhun.ftth.tenancy.domain.model.Tenant
import java.util.UUID

/** Port persistence untuk agregat [Tenant], dalam istilah domain. */
interface TenantRepository {

    fun save(tenant: Tenant): Tenant

    fun findById(id: UUID): Tenant?

    fun findBySlug(slug: String): Tenant?

    fun findAll(pageRequest: PageRequest): Page<Tenant>

    /** Id seluruh tenant berstatus ACTIVE — untuk pemindaian terjadwal lintas-tenant. */
    fun findActiveIds(): List<UUID>

    fun findAllIds(): List<UUID> = buildList {
        var page = 0
        do {
            val batch = findAll(PageRequest(page++, 200))
            addAll(batch.content.map { it.id })
        } while (page < batch.totalPages)
    }
}
