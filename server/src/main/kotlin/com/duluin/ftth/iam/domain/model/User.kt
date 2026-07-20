package com.duluin.ftth.iam.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.iam.domain.model.vo.Email
import java.time.Instant
import java.util.UUID

/**
 * Agregat User. Menyimpan hash password (hashing dilakukan port di luar domain),
 * kumpulan role, dan kumpulan area (scope data). Menegakkan invariant nama/status.
 */
class User private constructor(
    val id: UUID,
    val tenantId: UUID,
    email: Email,
    name: String,
    passwordHash: String,
    status: UserStatus,
    val platformAdmin: Boolean,
    roleIds: Set<UUID>,
    areaIds: Set<UUID>,
    val createdAt: Instant,
) {
    var email: Email = email
        private set

    var name: String = name
        private set

    var passwordHash: String = passwordHash
        private set

    var status: UserStatus = status
        private set

    var roleIds: Set<UUID> = roleIds
        private set

    var areaIds: Set<UUID> = areaIds
        private set

    val active: Boolean get() = status == UserStatus.ACTIVE

    fun rename(newName: String) {
        name = validateName(newName)
    }

    fun changePasswordHash(newHash: String) {
        require(newHash.isNotBlank()) { "Hash password kosong" }
        passwordHash = newHash
    }

    fun enable() {
        status = UserStatus.ACTIVE
    }

    fun disable() {
        status = UserStatus.DISABLED
    }

    fun assignRoles(roleIds: Set<UUID>) {
        this.roleIds = roleIds
    }

    fun assignAreas(areaIds: Set<UUID>) {
        this.areaIds = areaIds
    }

    companion object {
        fun create(
            tenantId: UUID,
            email: Email,
            name: String,
            passwordHash: String,
            platformAdmin: Boolean = false,
            roleIds: Set<UUID> = emptySet(),
            areaIds: Set<UUID> = emptySet(),
        ): User = User(
            id = UuidV7.generate(),
            tenantId = tenantId,
            email = email,
            name = validateName(name),
            passwordHash = passwordHash,
            status = UserStatus.ACTIVE,
            platformAdmin = platformAdmin,
            roleIds = roleIds,
            areaIds = areaIds,
            createdAt = Instant.now(),
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            email: Email,
            name: String,
            passwordHash: String,
            status: UserStatus,
            platformAdmin: Boolean,
            roleIds: Set<UUID>,
            areaIds: Set<UUID>,
            createdAt: Instant,
        ): User = User(id, tenantId, email, name, passwordHash, status, platformAdmin, roleIds, areaIds, createdAt)

        private fun validateName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.length !in 2..255) throw ValidationException("Nama user harus 2-255 karakter")
            return trimmed
        }
    }
}
