package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.outbound.RoleRepository
import com.duluin.ftth.iam.domain.model.Role
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class RolePersistenceAdapter(
    private val jpa: RoleJpaRepository,
) : RoleRepository {

    override fun save(role: Role): Role {
        val entity = jpa.findById(role.id).orElse(null)?.apply {
            name = role.name
            description = role.description
            permissionIds = role.permissionIds.toMutableSet()
        } ?: RoleJpaEntity(
            id = role.id,
            name = role.name,
            description = role.description,
            systemRole = role.systemRole,
            permissionIds = role.permissionIds.toMutableSet(),
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Role? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByName(name: String): Role? = jpa.findByName(name)?.toDomain()

    override fun findAll(): List<Role> = jpa.findAll().map { it.toDomain() }

    override fun findAllByIds(ids: Set<UUID>): List<Role> = jpa.findAllById(ids).map { it.toDomain() }

    override fun existsByName(name: String): Boolean = jpa.existsByName(name)

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

// tenantId di-coalesce ke context: pada insert baru, Hibernate baru menulis balik
// kolom @TenantId saat flush; operasi selalu berjalan dalam tenant context.
private fun RoleJpaEntity.toDomain(): Role =
    Role.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        name = name,
        description = description,
        systemRole = systemRole,
        permissionIds = permissionIds.toSet(),
    )
