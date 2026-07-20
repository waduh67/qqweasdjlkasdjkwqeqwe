package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.iam.application.port.inbound.AssignAccessCommand
import com.duluin.ftth.iam.application.port.inbound.CreateUserCommand
import com.duluin.ftth.iam.application.port.inbound.ManageUserUseCase
import com.duluin.ftth.iam.application.port.inbound.UpdateUserCommand
import com.duluin.ftth.iam.application.port.inbound.UserView
import com.duluin.ftth.iam.application.port.outbound.AreaRepository
import com.duluin.ftth.iam.application.port.outbound.PasswordHasher
import com.duluin.ftth.iam.application.port.outbound.RefreshTokenRepository
import com.duluin.ftth.iam.application.port.outbound.RoleRepository
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.domain.model.User
import com.duluin.ftth.iam.domain.model.vo.Email
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class UserService(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val areaRepository: AreaRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordHasher: PasswordHasher,
    private val currentUser: CurrentUserProvider,
    private val events: ApplicationEventPublisher,
) : ManageUserUseCase {

    override fun create(command: CreateUserCommand): UserView {
        val email = Email.of(command.email)
        if (userRepository.existsByEmail(email)) throw ConflictException("Email '${email.value}' sudah dipakai")
        validatePassword(command.password)
        val user = userRepository.save(
            User.create(
                tenantId = currentUser.current().tenantId,
                email = email,
                name = command.name,
                passwordHash = passwordHasher.hash(command.password),
                platformAdmin = false,
                roleIds = validateRoles(command.roleIds),
                areaIds = validateAreas(command.areaIds),
            ),
        )
        audit("user.created", user)
        return user.toView()
    }

    override fun update(id: UUID, command: UpdateUserCommand): UserView {
        val user = load(id)
        user.rename(command.name)
        val saved = userRepository.save(user)
        audit("user.updated", saved)
        return saved.toView()
    }

    override fun assignAccess(id: UUID, command: AssignAccessCommand): UserView {
        val user = load(id)
        user.assignRoles(validateRoles(command.roleIds))
        user.assignAreas(validateAreas(command.areaIds))
        val saved = userRepository.save(user)
        // Cabut refresh token agar perubahan izin berlaku setelah access-token kadaluarsa.
        refreshTokens.revokeAllForUser(id)
        audit("user.access_changed", saved)
        return saved.toView()
    }

    override fun setEnabled(id: UUID, enabled: Boolean): UserView {
        val user = load(id)
        if (enabled) {
            user.enable()
        } else {
            requireNotSelf(id, "menonaktifkan")
            user.disable()
            refreshTokens.revokeAllForUser(id)
        }
        val saved = userRepository.save(user)
        audit(if (enabled) "user.enabled" else "user.disabled", saved)
        return saved.toView()
    }

    override fun delete(id: UUID) {
        val user = load(id)
        requireNotSelf(id, "menghapus")
        userRepository.deleteById(id)
        refreshTokens.revokeAllForUser(id)
        audit("user.deleted", user)
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID): UserView = load(id).toView()

    @Transactional(readOnly = true)
    override fun list(query: String?, pageRequest: PageRequest): Page<UserView> =
        userRepository.search(query, pageRequest).map { it.toView() }

    private fun load(id: UUID): User =
        userRepository.findById(id) ?: throw NotFoundException("User $id tidak ditemukan")

    private fun requireNotSelf(id: UUID, action: String) {
        if (currentUser.currentOrNull()?.userId == id) {
            throw ValidationException("Tidak bisa $action akun sendiri")
        }
    }

    private fun validatePassword(password: String) {
        if (password.length < 8) throw ValidationException("Password minimal 8 karakter")
    }

    private fun validateRoles(ids: Set<UUID>): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        if (roleRepository.findAllByIds(ids).size != ids.size) {
            throw ValidationException("Ada roleId yang tidak dikenal")
        }
        return ids
    }

    private fun validateAreas(ids: Set<UUID>): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        if (areaRepository.findAllByIds(ids).size != ids.size) {
            throw ValidationException("Ada areaId yang tidak dikenal")
        }
        return ids
    }

    private fun audit(action: String, user: User) {
        val actor = currentUser.currentOrNull()
        events.publishEvent(
            AuditTrailEvent(
                tenantId = user.tenantId,
                actorId = actor?.userId,
                actorEmail = actor?.email,
                action = action,
                entityType = "User",
                entityId = user.id.toString(),
                detail = mapOf("email" to user.email.value),
            ),
        )
    }
}
