package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.outbound.AreaRepository
import com.duluin.ftth.iam.domain.model.Area
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AreaPersistenceAdapter(
    private val jpa: AreaJpaRepository,
) : AreaRepository {

    override fun save(area: Area): Area {
        val entity = jpa.findById(area.id).orElse(null)?.apply {
            name = area.name
            parentId = area.parentId
        } ?: AreaJpaEntity(
            id = area.id,
            code = area.code,
            name = area.name,
            parentId = area.parentId,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Area? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAll(): List<Area> = jpa.findAll().map { it.toDomain() }

    override fun findAllByIds(ids: Set<UUID>): List<Area> = jpa.findAllById(ids).map { it.toDomain() }

    override fun existsByCode(code: String): Boolean = jpa.existsByCode(code)

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

private fun AreaJpaEntity.toDomain(): Area =
    Area.rehydrate(id = id, tenantId = tenantId ?: TenantContext.tenantId(), code = code, name = name, parentId = parentId)
