package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.iam.application.port.outbound.PermissionRepository
import com.duluin.ftth.iam.domain.model.Permission
import com.duluin.ftth.iam.domain.model.vo.PermissionCode
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class PermissionPersistenceAdapter(
    private val jpa: PermissionJpaRepository,
) : PermissionRepository {

    override fun save(permission: Permission): Permission {
        val entity = jpa.findById(permission.id).orElse(null)?.apply {
            description = permission.description
            active = permission.active
        } ?: PermissionJpaEntity(
            id = permission.id,
            code = permission.code.value,
            module = permission.code.module,
            resource = permission.code.resource,
            action = permission.code.action,
            description = permission.description,
            platformOnly = permission.platformOnly,
            active = permission.active,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findAll(): List<Permission> = jpa.findAll().map { it.toDomain() }

    override fun findAllByIds(ids: Set<UUID>): List<Permission> =
        jpa.findAllById(ids).map { it.toDomain() }
}

private fun PermissionJpaEntity.toDomain(): Permission =
    Permission.rehydrate(
        id = id,
        code = PermissionCode.of(code),
        description = description,
        platformOnly = platformOnly,
        active = active,
    )
