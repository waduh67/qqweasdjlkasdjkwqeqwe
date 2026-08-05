package com.duluin.ftth.portal.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.portal.application.port.outbound.PortalCredentialRepository
import com.duluin.ftth.portal.domain.model.PortalCredential
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Kredensial portal — tenant-aware (RLS), 1:1 per pelanggan (constraint di migrasi). */
@Entity
@Table(name = "portal_credential")
class PortalCredentialJpaEntity(
    id: UUID,

    @Column(name = "customer_id", nullable = false, updatable = false)
    var customerId: UUID,

    @Column(name = "login", nullable = false)
    var login: String,

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,

    @Column(name = "disabled_at")
    var disabledAt: Instant?,
) : TenantAwareJpaEntity(id)

interface PortalCredentialJpaRepository : JpaRepository<PortalCredentialJpaEntity, UUID> {

    fun findByLogin(login: String): PortalCredentialJpaEntity?

    fun findByCustomerId(customerId: UUID): PortalCredentialJpaEntity?
}

@Component
class PortalCredentialPersistenceAdapter(
    private val jpa: PortalCredentialJpaRepository,
) : PortalCredentialRepository {

    override fun save(credential: PortalCredential): PortalCredential {
        val entity = jpa.findById(credential.id).orElse(null)?.apply {
            login = credential.login
            passwordHash = credential.passwordHash
            disabledAt = credential.disabledAt
        } ?: PortalCredentialJpaEntity(
            id = credential.id,
            customerId = credential.customerId,
            login = credential.login,
            passwordHash = credential.passwordHash,
            disabledAt = credential.disabledAt,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findByLogin(login: String): PortalCredential? =
        jpa.findByLogin(login)?.toDomain()

    override fun findByCustomerId(customerId: UUID): PortalCredential? =
        jpa.findByCustomerId(customerId)?.toDomain()
}

private fun PortalCredentialJpaEntity.toDomain(): PortalCredential =
    PortalCredential.rehydrate(
        id = id,
        customerId = customerId,
        login = login,
        passwordHash = passwordHash,
        disabledAt = disabledAt,
    )
