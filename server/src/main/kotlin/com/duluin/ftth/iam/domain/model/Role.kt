package com.duluin.ftth.iam.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/**
 * Role = kumpulan izin yang bisa dibuat/diedit tenant dari UI. [systemRole] menandai
 * role bawaan (mis. "Tenant Admin") yang tidak boleh dihapus.
 *
 * Menyimpan [permissionIds] sebagai bagian dari agregat; menegakkan invariant
 * bahwa sebuah role harus punya nama valid.
 */
class Role private constructor(
    val id: UUID,
    val tenantId: UUID,
    name: String,
    description: String?,
    val systemRole: Boolean,
    permissionIds: Set<UUID>,
) {
    var name: String = name
        private set

    var description: String? = description
        private set

    var permissionIds: Set<UUID> = permissionIds
        private set

    fun rename(newName: String) {
        name = validateName(newName)
    }

    fun updateDescription(newDescription: String?) {
        description = newDescription?.trim()?.takeIf(String::isNotEmpty)
    }

    fun replacePermissions(ids: Set<UUID>) {
        permissionIds = ids
    }

    companion object {
        fun create(
            tenantId: UUID,
            name: String,
            description: String? = null,
            systemRole: Boolean = false,
            permissionIds: Set<UUID> = emptySet(),
        ): Role = Role(
            id = UuidV7.generate(),
            tenantId = tenantId,
            name = validateName(name),
            description = description?.trim()?.takeIf(String::isNotEmpty),
            systemRole = systemRole,
            permissionIds = permissionIds,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            name: String,
            description: String?,
            systemRole: Boolean,
            permissionIds: Set<UUID>,
        ): Role = Role(id, tenantId, name, description, systemRole, permissionIds)

        private fun validateName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.length !in 2..100) throw ValidationException("Nama role harus 2-100 karakter")
            return trimmed
        }
    }
}
