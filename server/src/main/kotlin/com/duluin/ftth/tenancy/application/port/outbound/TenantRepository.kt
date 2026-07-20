package com.duluin.ftth.tenancy.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.tenancy.domain.model.Tenant
import java.util.UUID

/** Port persistence untuk agregat [Tenant], dalam istilah domain. */
interface TenantRepository {

    fun save(tenant: Tenant): Tenant

    fun findById(id: UUID): Tenant?

    fun findBySlug(slug: String): Tenant?

    fun findAll(pageRequest: PageRequest): Page<Tenant>
}
