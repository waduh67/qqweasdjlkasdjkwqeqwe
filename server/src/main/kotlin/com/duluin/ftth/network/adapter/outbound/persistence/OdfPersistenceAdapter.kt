package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.geo.Geometries
import com.duluin.ftth.common.infrastructure.persistence.geo.toCoordinate
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.application.port.outbound.OdfRepository
import com.duluin.ftth.network.domain.model.Odf
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OdfPersistenceAdapter(
    private val jpa: OdfJpaRepository,
) : OdfRepository {

    override fun save(odf: Odf): Odf {
        val entity = jpa.findById(odf.id).orElse(null)?.apply {
            name = odf.name
            siteId = odf.siteId
            location = Geometries.point(odf.location)
            areaId = odf.areaId
            portCount = odf.portCount
            status = odf.status
        } ?: OdfJpaEntity(
            id = odf.id,
            code = odf.code,
            name = odf.name,
            siteId = odf.siteId,
            location = Geometries.point(odf.location),
            areaId = odf.areaId,
            portCount = odf.portCount,
            status = odf.status,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Odf? = jpa.findById(id).orElse(null)?.toDomain()

    override fun search(query: String, areaIds: Set<UUID>?, pageRequest: PageRequest): Page<Odf> {
        val spec = NetworkSpecifications.textMatches<OdfJpaEntity>(query)
            .and(NetworkSpecifications.withinAreas(areaIds))
        return jpa.findAll(spec, pageRequest.toPageable()).toDomainPage().map { it.toDomain() }
    }

    override fun existsByCode(code: String): Boolean = jpa.existsByCode(code)

    override fun countBySiteId(siteId: UUID): Long = jpa.countBySiteId(siteId)

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

internal fun OdfJpaEntity.toDomain(): Odf = Odf.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    code = code,
    name = name,
    siteId = siteId,
    location = location.toCoordinate(),
    areaId = areaId,
    portCount = portCount,
    status = status,
)
