package com.duluin.ftth.iam.application.port.outbound

import com.duluin.ftth.iam.domain.model.Role
import java.util.UUID

interface RoleRepository {

    fun save(role: Role): Role

    fun findById(id: UUID): Role?

    fun findByName(name: String): Role?

    fun findAll(): List<Role>

    fun findAllByIds(ids: Set<UUID>): List<Role>

    fun existsByName(name: String): Boolean

    fun deleteById(id: UUID)
}
