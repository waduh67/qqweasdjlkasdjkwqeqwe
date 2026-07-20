package com.duluin.ftth.iam.application.service

import com.duluin.ftth.iam.application.port.outbound.PasswordHasher
import com.duluin.ftth.iam.application.port.outbound.PermissionRepository
import com.duluin.ftth.iam.application.port.outbound.RoleRepository
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.domain.model.Role
import com.duluin.ftth.iam.domain.model.User
import com.duluin.ftth.iam.domain.model.vo.Email
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Menyediakan role admin bawaan + user admin awal. Berjalan DI DALAM tenant
 * context yang dipasang pemanggil (batas `@Transactional` di sini agar session
 * Hibernate terbuka dengan tenant yang benar). Semua operasi idempotent.
 */
@Service
@Transactional
class AdminProvisioner(
    private val roleRepository: RoleRepository,
    private val userRepository: UserRepository,
    private val permissionRepository: PermissionRepository,
    private val passwordHasher: PasswordHasher,
) {
    /** Role "Tenant Admin" (semua izin non-platform) + admin tenant. */
    fun provisionTenantAdmin(tenantId: UUID, email: String, name: String, password: String): Boolean {
        val roleId = ensureRole(tenantId, "Tenant Admin", "Akses penuh dalam tenant", tenantPermissionIds())
        return ensureAdminUser(tenantId, email, name, password, platformAdmin = false, roleIds = setOf(roleId))
    }

    /** Role "Super Admin" (semua izin termasuk platform) + platform admin. */
    fun provisionPlatformAdmin(tenantId: UUID, email: String, name: String, password: String): Boolean {
        val roleId = ensureRole(tenantId, "Super Admin", "Akses penuh platform", allPermissionIds())
        return ensureAdminUser(tenantId, email, name, password, platformAdmin = true, roleIds = setOf(roleId))
    }

    private fun ensureRole(tenantId: UUID, name: String, description: String, permissionIds: Set<UUID>): UUID {
        val existing = roleRepository.findByName(name)
        return when {
            existing == null ->
                roleRepository.save(Role.create(tenantId, name, description, systemRole = true, permissionIds)).id

            existing.permissionIds != permissionIds -> {
                existing.replacePermissions(permissionIds)
                roleRepository.save(existing).id
            }

            else -> existing.id
        }
    }

    private fun ensureAdminUser(
        tenantId: UUID,
        email: String,
        name: String,
        password: String,
        platformAdmin: Boolean,
        roleIds: Set<UUID>,
    ): Boolean {
        val parsed = Email.of(email)
        if (userRepository.existsByEmail(parsed)) return false
        userRepository.save(
            User.create(
                tenantId = tenantId,
                email = parsed,
                name = name,
                passwordHash = passwordHasher.hash(password),
                platformAdmin = platformAdmin,
                roleIds = roleIds,
            ),
        )
        return true
    }

    private fun tenantPermissionIds(): Set<UUID> =
        permissionRepository.findAll().filterNot { it.platformOnly }.mapTo(HashSet()) { it.id }

    private fun allPermissionIds(): Set<UUID> =
        permissionRepository.findAll().mapTo(HashSet()) { it.id }
}
