package com.duluin.ftth.tenancy.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.tenancy.application.port.outbound.TenantRepository
import com.duluin.ftth.tenancy.domain.model.Tenant
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Mengimplementasikan port [TenantRepository] dengan Spring Data JPA, memetakan
 * antara agregat domain [Tenant] dan [TenantJpaEntity].
 */
@Component
class TenantPersistenceAdapter(
    private val jpa: TenantJpaRepository,
) : TenantRepository {

    override fun save(tenant: Tenant): Tenant {
        val entity = jpa.findById(tenant.id).orElse(null)
            ?.apply {
                slug = tenant.slug
                name = tenant.name
                status = tenant.status
            }
            ?: TenantJpaEntity(tenant.id, tenant.slug, tenant.name, tenant.status)
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Tenant? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findBySlug(slug: String): Tenant? = jpa.findBySlug(slug)?.toDomain()

    override fun findAll(pageRequest: PageRequest): Page<Tenant> =
        jpa.findAll(pageRequest.toPageable()).map(TenantJpaEntity::toDomain).toDomainPage()
}

private fun TenantJpaEntity.toDomain(): Tenant =
    Tenant.rehydrate(id = id, slug = slug, name = name, status = status)
