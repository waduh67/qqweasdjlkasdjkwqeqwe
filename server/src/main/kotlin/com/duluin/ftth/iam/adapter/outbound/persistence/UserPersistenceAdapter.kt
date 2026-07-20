package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.domain.model.User
import com.duluin.ftth.iam.domain.model.vo.Email
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class UserPersistenceAdapter(
    private val jpa: UserJpaRepository,
) : UserRepository {

    override fun save(user: User): User {
        val entity = jpa.findById(user.id).orElse(null)?.apply {
            email = user.email.value
            name = user.name
            passwordHash = user.passwordHash
            status = user.status
            roleIds = user.roleIds.toMutableSet()
            areaIds = user.areaIds.toMutableSet()
        } ?: UserJpaEntity(
            id = user.id,
            email = user.email.value,
            name = user.name,
            passwordHash = user.passwordHash,
            status = user.status,
            platformAdmin = user.platformAdmin,
            roleIds = user.roleIds.toMutableSet(),
            areaIds = user.areaIds.toMutableSet(),
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): User? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByEmail(email: Email): User? = jpa.findByEmail(email.value)?.toDomain()

    override fun existsByEmail(email: Email): Boolean = jpa.existsByEmail(email.value)

    override fun search(query: String?, pageRequest: PageRequest): Page<User> =
        jpa.search(query?.trim()?.lowercase().orEmpty(), pageRequest.toPageable())
            .map { it.toDomain() }
            .toDomainPage()

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

private fun UserJpaEntity.toDomain(): User =
    User.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        email = Email.of(email),
        name = name,
        passwordHash = passwordHash,
        status = status,
        platformAdmin = platformAdmin,
        roleIds = roleIds.toSet(),
        areaIds = areaIds.toSet(),
        createdAt = createdAt,
    )
