package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.geo.Geometries
import com.duluin.ftth.common.infrastructure.persistence.geo.toCoordinate
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.domain.model.Odc
import com.duluin.ftth.network.domain.model.vo.SplitterRatio
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OdcPersistenceAdapter(
    private val jpa: OdcJpaRepository,
) : OdcRepository {

    override fun save(odc: Odc): Odc {
        val entity = jpa.findById(odc.id).orElse(null)?.apply {
            name = odc.name
            address = odc.address
            location = Geometries.point(odc.location)
            areaId = odc.areaId
            ponPortId = odc.ponPortId
            splitterRatio = odc.splitterRatio.label
            capacity = odc.capacity
            status = odc.status
        } ?: OdcJpaEntity(
            id = odc.id,
            code = odc.code,
            name = odc.name,
            address = odc.address,
            location = Geometries.point(odc.location),
            areaId = odc.areaId,
            ponPortId = odc.ponPortId,
            splitterRatio = odc.splitterRatio.label,
            capacity = odc.capacity,
            status = odc.status,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Odc? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAllByIds(ids: Set<UUID>): List<Odc> = jpa.findAllById(ids).map { it.toDomain() }

    override fun search(query: String, areaIds: Set<UUID>?, pageRequest: PageRequest): Page<Odc> {
        val spec = NetworkSpecifications.textMatches<OdcJpaEntity>(query)
            .and(NetworkSpecifications.withinAreas(areaIds))
        return jpa.findAll(spec, pageRequest.toPageable()).toDomainPage().map { it.toDomain() }
    }

    override fun existsByCode(code: String): Boolean = jpa.existsByCode(code)

    override fun countByPonPortId(ponPortId: UUID): Long = jpa.countByPonPortId(ponPortId)

    override fun countByPonPortIds(ponPortIds: Set<UUID>): Map<UUID, Long> =
        if (ponPortIds.isEmpty()) emptyMap()
        else jpa.countGroupedByPonPort(ponPortIds).associate { it.parentId to it.total }

    override fun findIdsByPonPortIds(ponPortIds: Set<UUID>): Set<UUID> =
        if (ponPortIds.isEmpty()) emptySet() else jpa.findIdsByPonPortIds(ponPortIds)

    override fun findByPonPortId(ponPortId: UUID): List<Odc> =
        jpa.findByPonPortIdOrderByCode(ponPortId).map { it.toDomain() }

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

internal fun OdcJpaEntity.toDomain(): Odc = Odc.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    code = code,
    name = name,
    address = address,
    location = location.toCoordinate(),
    areaId = areaId,
    ponPortId = ponPortId,
    splitterRatio = SplitterRatio.of(splitterRatio),
    capacity = capacity,
    status = status,
)
