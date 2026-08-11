package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.geo.Geometries
import com.duluin.ftth.common.infrastructure.persistence.geo.toCoordinate
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.application.port.outbound.JointBoxRepository
import com.duluin.ftth.network.domain.model.JointBox
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class JointBoxPersistenceAdapter(
    private val jpa: JointBoxJpaRepository,
) : JointBoxRepository {

    override fun save(jointBox: JointBox): JointBox {
        val entity = jpa.findById(jointBox.id).orElse(null)?.apply {
            name = jointBox.name
            address = jointBox.address
            location = Geometries.point(jointBox.location)
            areaId = jointBox.areaId
            trayCount = jointBox.trayCount
            capacity = jointBox.capacity
            status = jointBox.status
            installedOn = jointBox.installedOn
            mounting = jointBox.mounting
            notes = jointBox.notes
        } ?: JointBoxJpaEntity(
            id = jointBox.id,
            code = jointBox.code,
            name = jointBox.name,
            address = jointBox.address,
            location = Geometries.point(jointBox.location),
            areaId = jointBox.areaId,
            trayCount = jointBox.trayCount,
            capacity = jointBox.capacity,
            status = jointBox.status,
            installedOn = jointBox.installedOn,
            mounting = jointBox.mounting,
            notes = jointBox.notes,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): JointBox? = jpa.findById(id).orElse(null)?.toDomain()

    override fun search(query: String, areaIds: Set<UUID>?, pageRequest: PageRequest): Page<JointBox> {
        val spec = NetworkSpecifications.textMatches<JointBoxJpaEntity>(query)
            .and(NetworkSpecifications.withinAreas(areaIds))
        return jpa.findAll(spec, pageRequest.toPageable()).toDomainPage().map { it.toDomain() }
    }

    override fun existsByCode(code: String): Boolean = jpa.existsByCode(code)

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

internal fun JointBoxJpaEntity.toDomain(): JointBox = JointBox.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    code = code,
    name = name,
    address = address,
    location = location.toCoordinate(),
    areaId = areaId,
    trayCount = trayCount,
    capacity = capacity,
    status = status,
    installedOn = installedOn,
    mounting = mounting,
    notes = notes,
)
