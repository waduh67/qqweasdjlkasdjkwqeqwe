package com.karuhun.ftth.iam.application.port.outbound

import com.karuhun.ftth.iam.domain.model.Role
import java.util.UUID

interface RoleRepository {

    fun save(role: Role): Role

    fun findById(id: UUID): Role?

    fun findByName(name: String): Role?

    fun findByDefaultKey(key: String): Role? = findAll().singleOrNull { it.defaultKey == key }

    fun findAll(): List<Role>

    fun findAllByIds(ids: Set<UUID>): List<Role>

    fun existsByName(name: String): Boolean

    fun deleteById(id: UUID)
}
