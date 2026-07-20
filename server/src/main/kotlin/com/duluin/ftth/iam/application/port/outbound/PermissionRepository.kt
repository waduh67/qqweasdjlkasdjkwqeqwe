package com.duluin.ftth.iam.application.port.outbound

import com.duluin.ftth.iam.domain.model.Permission
import java.util.UUID

/** Port untuk data referensi izin (platform-level, tanpa tenant). */
interface PermissionRepository {

    fun save(permission: Permission): Permission

    fun findAll(): List<Permission>

    fun findAllByIds(ids: Set<UUID>): List<Permission>
}
