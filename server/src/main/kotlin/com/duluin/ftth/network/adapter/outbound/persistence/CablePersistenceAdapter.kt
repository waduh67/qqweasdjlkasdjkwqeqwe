package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
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
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CablePersistenceAdapter(
    private val jpa: CableJpaRepository,
) : CableRepository {

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
