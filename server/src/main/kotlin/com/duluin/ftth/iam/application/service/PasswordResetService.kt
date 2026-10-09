package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.iam.application.port.outbound.PasswordHasher
import com.duluin.ftth.iam.application.port.outbound.RefreshTokenRepository
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.domain.model.User
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class PasswordResetService(
    private val users: UserRepository,
    private val passwords: PasswordHasher,
    private val refresh: RefreshTokenRepository,
    private val current: CurrentUserProvider,
    private val events: ApplicationEventPublisher,
    private val authority: com.duluin.ftth.iam.CurrentAuthorityApi,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun reset(user: User, password: String) {
        if (password.length < 8) throw ValidationException("Password minimal 8 karakter")
        authority.lockForChange().incrementEpoch()
        user.changePasswordHash(passwords.hash(password))
        users.save(user)
        refresh.revokeAllForUser(user.id)
        val actor = current.current()
        events.publishEvent(AuditTrailEvent(tenantId = user.tenantId, actorId = actor.userId,
            actorEmail = actor.email, action = "user.password_reset", entityType = "User",
            entityId = user.id.toString(), detail = mapOf("email" to user.email.value)))
    }
}
