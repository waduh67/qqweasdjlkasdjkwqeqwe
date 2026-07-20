package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.iam.application.port.inbound.CreateRoleCommand
import com.duluin.ftth.iam.application.port.inbound.ManageRoleUseCase
import com.duluin.ftth.iam.application.port.inbound.RoleView
import com.duluin.ftth.iam.application.port.inbound.UpdateRoleCommand
import com.duluin.ftth.iam.application.port.outbound.PermissionRepository
import com.duluin.ftth.iam.application.port.outbound.RoleRepository
import com.duluin.ftth.iam.domain.model.Role
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class RoleService(
    private val roleRepository: RoleRepository,
    private val permissionRepository: PermissionRepository,
    private val currentUser: CurrentUserProvider,
    private val events: ApplicationEventPublisher,
) : ManageRoleUseCase {

    override fun create(command: CreateRoleCommand): RoleView {
        if (roleRepository.existsByName(command.name.trim())) {
            throw ConflictException("Role '${command.name}' sudah ada")
        }
        val permissionIds = validatePermissions(command.permissionIds)
        val role = roleRepository.save(
            Role.create(
                tenantId = currentUser.current().tenantId,
                name = command.name,
                description = command.description,
                systemRole = false,
                permissionIds = permissionIds,
            ),
        )
        audit("role.created", role)
        return role.toView()
    }

    override fun update(id: UUID, command: UpdateRoleCommand): RoleView {
        val role = load(id)
        val newName = command.name.trim()
        if (!role.name.equals(newName, ignoreCase = false) && roleRepository.existsByName(newName)) {
            throw ConflictException("Role '$newName' sudah ada")
        }
        val permissionIds = validatePermissions(command.permissionIds)
        role.rename(command.name)
        role.updateDescription(command.description)
        role.replacePermissions(permissionIds)
        val saved = roleRepository.save(role)
        audit("role.updated", saved)
        return saved.toView()
    }

    override fun delete(id: UUID) {
        val role = load(id)
        if (role.systemRole) throw ValidationException("Role sistem tidak bisa dihapus")
        roleRepository.deleteById(id)
        audit("role.deleted", role)
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID): RoleView = load(id).toView()

    @Transactional(readOnly = true)
    override fun list(): List<RoleView> =
        roleRepository.findAll().map { it.toView() }.sortedBy { it.name }

    private fun load(id: UUID): Role =
        roleRepository.findById(id) ?: throw NotFoundException("Role $id tidak ditemukan")

    /** Pastikan semua id izin dikenal, dan cegah tenant biasa memberi izin platform. */
    private fun validatePermissions(ids: Set<UUID>): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        val permissions = permissionRepository.findAllByIds(ids)
        if (permissions.size != ids.size) throw ValidationException("Ada permissionId yang tidak dikenal")
        if (!currentUser.current().platformAdmin && permissions.any { it.platformOnly }) {
            throw AccessDeniedException("Tidak boleh memberikan izin platform")
        }
        return ids
    }

    private fun audit(action: String, role: Role) {
        val actor = currentUser.currentOrNull()
        events.publishEvent(
            AuditTrailEvent(
                tenantId = role.tenantId,
                actorId = actor?.userId,
                actorEmail = actor?.email,
                action = action,
                entityType = "Role",
                entityId = role.id.toString(),
                detail = mapOf("name" to role.name),
            ),
        )
    }
}
