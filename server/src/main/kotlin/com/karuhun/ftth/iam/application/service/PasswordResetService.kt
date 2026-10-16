package com.karuhun.ftth.iam.application.service

import com.karuhun.ftth.common.audit.AuditTrailEvent
import com.karuhun.ftth.common.domain.error.ValidationException
import com.karuhun.ftth.common.security.CurrentUserProvider
import com.karuhun.ftth.iam.application.port.outbound.PasswordHasher
import com.karuhun.ftth.iam.application.port.outbound.RefreshTokenRepository
import com.karuhun.ftth.iam.application.port.outbound.UserRepository
import com.karuhun.ftth.iam.domain.model.User
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
    private val authority: com.karuhun.ftth.iam.CurrentAuthorityApi,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun reset(user: User, password: String, action: String = "user.password_reset") {
        if (password.length < 8) throw ValidationException("Password minimal 8 karakter")
        if (password.toByteArray(Charsets.UTF_8).size > 72) throw ValidationException("Password terlalu panjang. Gunakan password yang lebih pendek")
        authority.lockForChange().incrementEpoch()
        user.changePasswordHash(passwords.hash(password))
        users.save(user)
        refresh.revokeAllForUser(user.id)
        val actor = current.current()
        events.publishEvent(AuditTrailEvent(tenantId = user.tenantId, actorId = actor.userId,
            actorEmail = actor.email, action = action, entityType = "User",
            entityId = user.id.toString(), detail = mapOf("email" to user.email.value)))
    }
}
