package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.geo.Geometries
import com.duluin.ftth.common.infrastructure.persistence.geo.toCoordinate
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.application.port.outbound.SiteRepository
import com.duluin.ftth.network.domain.model.Site
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SitePersistenceAdapter(
    private val jpa: SiteJpaRepository,
) : SiteRepository {

    override fun save(site: Site): Site {
        val entity = jpa.findById(site.id).orElse(null)?.apply {
            name = site.name
            address = site.address
            location = Geometries.point(site.location)
            areaId = site.areaId
        } ?: SiteJpaEntity(
            id = site.id,
            code = site.code,
            name = site.name,
            address = site.address,
            location = Geometries.point(site.location),
            areaId = site.areaId,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Site? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAllByIds(ids: Set<UUID>): List<Site> = jpa.findAllById(ids).map { it.toDomain() }

    override fun search(query: String, pageRequest: PageRequest): Page<Site> =
        jpa.findAll(NetworkSpecifications.textMatches<SiteJpaEntity>(query), pageRequest.toPageable())
            .toDomainPage()
            .map { it.toDomain() }

    override fun existsByCode(code: String): Boolean = jpa.existsByCode(code)

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

internal fun SiteJpaEntity.toDomain(): Site = Site.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    code = code,
    name = name,
    address = address,
    location = location.toCoordinate(),
    areaId = areaId,
)
