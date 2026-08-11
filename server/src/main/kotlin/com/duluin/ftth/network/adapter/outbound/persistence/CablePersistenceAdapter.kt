package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.persistence.geo.Geometries
import com.duluin.ftth.common.infrastructure.persistence.geo.toRoutePath
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.NetworkEndpoint
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CablePersistenceAdapter(
    private val jpa: CableJpaRepository,
) : CableRepository {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun save(cable: Cable): Cable {
        val entity = jpa.findById(cable.id).orElse(null)?.apply {
            name = cable.name
            cableType = cable.cableType
            coreCount = cable.coreCount
            route = Geometries.lineString(cable.route)
            lengthMeters = cable.lengthMeters
            fromKind = cable.from.kind
            fromId = cable.from.id
            toKind = cable.to.kind
            toId = cable.to.id
            fromPonPortId = cable.from.ponPortId
            fromPortNumber = cable.from.portNumber
            toPortNumber = cable.to.portNumber
            status = cable.status
            installationMethod = cable.installation
            ownership = cable.ownership
        } ?: CableJpaEntity(
            id = cable.id,
            code = cable.code,
            name = cable.name,
            cableType = cable.cableType,
            coreCount = cable.coreCount,
            route = Geometries.lineString(cable.route),
            lengthMeters = cable.lengthMeters,
            fromKind = cable.from.kind,
            fromId = cable.from.id,
            toKind = cable.to.kind,
            toId = cable.to.id,
            fromPonPortId = cable.from.ponPortId,
            fromPortNumber = cable.from.portNumber,
            toPortNumber = cable.to.portNumber,
            status = cable.status,
            installationMethod = cable.installation,
            ownership = cable.ownership,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Cable? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByIds(ids: Collection<UUID>): List<Cable> =
        if (ids.isEmpty()) emptyList() else jpa.findAllById(ids).map { it.toDomain() }

    override fun search(query: String, cableType: CableType?, pageRequest: PageRequest): Page<Cable> {
        val spec = NetworkSpecifications.textMatches<CableJpaEntity>(query)
            .and(NetworkSpecifications.equals("cableType", cableType))
        return jpa.findAll(spec, pageRequest.toPageable()).toDomainPage().map { it.toDomain() }
    }

    override fun findByEndpoint(node: NetworkNodeRef): List<Cable> =
        jpa.findAll(NetworkSpecifications.endpointIs(node.kind, node.id)).map { it.toDomain() }

    override fun findByEndpointNodeIds(nodeIds: Set<UUID>): List<Cable> =
        if (nodeIds.isEmpty()) emptyList()
        else jpa.findAll(NetworkSpecifications.endpointInNodes(nodeIds)).map { it.toDomain() }

    /**
     * PENTING — dijalankan lewat [EntityManager], BUKAN `JdbcTemplate`. GUC
     * `app.tenant_id` yang menghidupkan Row-Level Security hanya menempel pada
     * connection pinjaman Hibernate; connection mentah dari pool akan ditolak RLS
     * dan hasilnya kosong tanpa penjelasan.
     *
     * Jarak diukur pada `geography` supaya satuannya meter sejati, bukan derajat —
     * di lintang Indonesia satu derajat bujur ±111 km, dan toleransi mid-span
     * yang salah satuan akan menyapu setengah kota.
     */
    override fun findPassing(location: Coordinate, radiusMeters: Double): List<Cable> {
        val ids = entityManager.createNativeQuery(
            """
            SELECT c.id::text FROM cable c
            WHERE ST_DWithin(
                c.route::geography,
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
        return findByIds(ids)
    }

    override fun existsByCode(code: String): Boolean = jpa.existsByCode(code)

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

internal fun CableJpaEntity.toDomain(): Cable = Cable.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    code = code,
    name = name,
    cableType = cableType,
    coreCount = coreCount,
    route = route.toRoutePath(),
    from = NetworkEndpoint(fromKind, fromId, ponPortId = fromPonPortId, portNumber = fromPortNumber),
    to = NetworkEndpoint(toKind, toId, portNumber = toPortNumber),
    status = status,
    installation = installationMethod,
    ownership = ownership,
)
