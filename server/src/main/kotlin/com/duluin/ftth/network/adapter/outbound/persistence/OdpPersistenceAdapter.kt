package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.persistence.geo.Geometries
import com.duluin.ftth.common.infrastructure.persistence.geo.toCoordinate
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.domain.model.Odp
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OdpPersistenceAdapter(
    private val jpa: OdpJpaRepository,
) : OdpRepository {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun save(odp: Odp): Odp {
        val entity = jpa.findById(odp.id).orElse(null)?.apply {
            name = odp.name
            address = odp.address
            location = Geometries.point(odp.location)
            areaId = odp.areaId
            odcId = odp.odcId
            capacity = odp.capacity
            status = odp.status
            installedOn = odp.installedOn
            mounting = odp.mounting
            notes = odp.notes
        } ?: OdpJpaEntity(
            id = odp.id,
            code = odp.code,
            name = odp.name,
            address = odp.address,
            location = Geometries.point(odp.location),
            areaId = odp.areaId,
            odcId = odp.odcId,
            capacity = odp.capacity,
            status = odp.status,
            installedOn = odp.installedOn,
            mounting = odp.mounting,
            notes = odp.notes,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Odp? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAllByIds(ids: Set<UUID>): List<Odp> = jpa.findAllById(ids).map { it.toDomain() }

    override fun findAllInAreas(areaIds: Set<UUID>?): List<Odp> =
        jpa.findAll(NetworkSpecifications.withinAreas<OdpJpaEntity>(areaIds)).map { it.toDomain() }

    override fun search(query: String, areaIds: Set<UUID>?, odcId: UUID?, pageRequest: PageRequest): Page<Odp> {
        val spec = NetworkSpecifications.textMatches<OdpJpaEntity>(query)
            .and(NetworkSpecifications.withinAreas(areaIds))
            .and(NetworkSpecifications.equals("odcId", odcId))
        return jpa.findAll(spec, pageRequest.toPageable()).toDomainPage().map { it.toDomain() }
    }

    override fun findByOdcId(odcId: UUID): List<Odp> = jpa.findByOdcIdOrderByCode(odcId).map { it.toDomain() }

    override fun existsByCode(code: String): Boolean = jpa.existsByCode(code)

    override fun countByOdcId(odcId: UUID): Long = jpa.countByOdcId(odcId)

    override fun countByOdcIds(odcIds: Set<UUID>): Map<UUID, Long> =
        if (odcIds.isEmpty()) emptyMap()
        else jpa.countGroupedByOdc(odcIds).associate { it.parentId to it.total }

    override fun findIdsByOdcIds(odcIds: Set<UUID>): Set<UUID> =
        if (odcIds.isEmpty()) emptySet() else jpa.findIdsByOdcIds(odcIds)

    /**
     * PENTING — lewat [EntityManager], BUKAN `JdbcTemplate`: GUC `app.tenant_id`
     * yang menghidupkan RLS cuma menempel pada connection pinjaman Hibernate.
     * Alasan `geography` ada di kontraknya.
     */
    override fun findNear(location: Coordinate, radiusMeters: Double): List<Odp> {
        val ids = entityManager.createNativeQuery(
            """
            SELECT o.id::text FROM odp o
            WHERE ST_DWithin(
                o.location::geography,
                ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                :radius
            )
            """.trimIndent(),
        )
            .setParameter("lon", location.longitude)
            .setParameter("lat", location.latitude)
            .setParameter("radius", radiusMeters)
            .resultList
            .map { UUID.fromString(it as String) }
        return if (ids.isEmpty()) emptyList() else findAllByIds(ids.toSet())
    }

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

internal fun OdpJpaEntity.toDomain(): Odp = Odp.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    code = code,
    name = name,
    address = address,
    location = location.toCoordinate(),
    areaId = areaId,
    odcId = odcId,
    capacity = capacity,
    status = status,
    installedOn = installedOn,
    mounting = mounting,
    notes = notes,
)
