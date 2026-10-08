package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.OwnerPasswordReset
import com.duluin.ftth.iam.TenantOwnerApi
import com.duluin.ftth.iam.TenantOwnerRef
import com.duluin.ftth.iam.authorizeChange
import com.duluin.ftth.iam.application.port.outbound.TenantOwnerStore
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.domain.model.User
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantOwnerService(
    private val owners: TenantOwnerStore,
    private val users: UserRepository,
    private val tenants: TenantApi,
    private val authority: CurrentAuthorityApi,
    private val current: CurrentUserProvider,
    private val passwords: PasswordResetService,
    private val refresh: com.duluin.ftth.iam.application.port.outbound.RefreshTokenRepository,
    private val events: ApplicationEventPublisher,
    manager: PlatformTransactionManager,
) : TenantOwnerApi {
    private val transaction = TransactionTemplate(manager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    override fun findOwners(tenantIds: Set<UUID>): Map<UUID, TenantOwnerRef?> = platform("platform.tenant.view") {
        owners.findProfiles(tenantIds - tenants.platformTenantId()).let { profiles ->
            tenantIds.associateWith { profiles[it] }
        }
    }

    override fun candidates(tenantId: UUID, query: String?, page: PageRequest): Page<TenantOwnerRef> = platform("platform.tenant.manage") {
        scoped(tenantId) { users.search(query, page).map { it.toRef() } }
    }

    override fun bind(tenantId: UUID, userId: UUID): TenantOwnerRef = platform("platform.tenant.manage") {
        scoped(tenantId) {
            val fence = authority.lockForChange()
            val user = load(userId)
            if (user.platformAdmin || !user.active) throw ValidationException("Owner harus akun aktif milik tenant ini")
            val actor = current.current()
            val previous = owners.findUserId()
            owners.bind(userId, actor.userId)
            (listOf(user) + listOfNotNull(previous?.takeIf { it != userId }?.let(users::findById))).forEach { account ->
                account.invalidateSessions()
                users.save(account)
                refresh.revokeAllForUser(account.id)
            }
            fence.incrementEpoch()
            events.publishEvent(AuditTrailEvent(tenantId = tenantId, actorId = actor.userId, actorEmail = actor.email,
                action = "tenant.owner_changed", entityType = "Tenant", entityId = tenantId.toString(),
                detail = mapOf("previousOwnerUserId" to previous?.toString(), "ownerUserId" to userId.toString())))
            user.toRef()
        }
    }

    override fun resetPassword(tenantId: UUID, command: OwnerPasswordReset) = platform("platform.tenant.manage") {
        scoped(tenantId) {
            authority.lockForChange()
            val id = owners.findUserId() ?: throw ConflictException("Owner belum ditentukan")
            if (id != command.expectedOwnerUserId) throw ConflictException("Owner telah berubah. Muat ulang data tenant")
            val user = load(id)
            if (user.platformAdmin) throw ValidationException("Akun platform tidak dapat menjadi owner tenant")
            passwords.reset(user, command.newPassword)
        }
    }

    private fun <T : Any> platform(permission: String, action: () -> T): T {
        val actor = current.current()
        return TenantContext.runAs(actor.tenantId) {
            requireNotNull(transaction.execute { authority.authorizeChange(permission); action() })
        }
    }

    private fun <T : Any> scoped(tenant: UUID, action: () -> T): T {
        if (tenant == tenants.platformTenantId()) throw ValidationException("Tenant platform tidak memiliki owner ISP")
        tenants.requireById(tenant)
        return TenantContext.runAs(tenant) { requireNotNull(transaction.execute { action() }) }
    }

    private fun load(id: UUID): User = users.findById(id) ?: throw NotFoundException("User tidak ditemukan di tenant ini")
    private fun User.toRef() = TenantOwnerRef(id, name, email.value, status.name)
}
