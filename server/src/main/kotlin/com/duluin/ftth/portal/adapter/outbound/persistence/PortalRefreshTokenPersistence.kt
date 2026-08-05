package com.duluin.ftth.portal.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import com.duluin.ftth.portal.application.port.outbound.PortalRefreshTokenRepository
import com.duluin.ftth.portal.domain.model.PortalRefreshToken
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Refresh token portal — SENGAJA bukan tenant-aware: lookup by hash terjadi sebelum tenant
 * context terbentuk (saat refresh/logout). Kolom tenant_id tetap disimpan agar rotasi bisa
 * memasang tenant context yang benar.
 */
@Entity
@Table(name = "portal_refresh_token")
class PortalRefreshTokenJpaEntity(
    id: UUID,

    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID,

    @Column(name = "customer_id", nullable = false, updatable = false)
    var customerId: UUID,

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    var tokenHash: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "revoked_at")
    var revokedAt: Instant?,
) : BaseJpaEntity(id)

interface PortalRefreshTokenJpaRepository : JpaRepository<PortalRefreshTokenJpaEntity, UUID> {

    fun findByTokenHash(tokenHash: String): PortalRefreshTokenJpaEntity?

    @Modifying
    @Query(
        "update PortalRefreshTokenJpaEntity t set t.revokedAt = :now " +
            "where t.customerId = :customerId and t.revokedAt is null",
    )
    fun revokeAllForCustomer(@Param("customerId") customerId: UUID, @Param("now") now: Instant)
}

@Component
class PortalRefreshTokenPersistenceAdapter(
    private val jpa: PortalRefreshTokenJpaRepository,
) : PortalRefreshTokenRepository {

    override fun save(token: PortalRefreshToken): PortalRefreshToken {
        val entity = jpa.findById(token.id).orElse(null)?.apply {
            revokedAt = token.revokedAt
        } ?: PortalRefreshTokenJpaEntity(
            id = token.id,
            tenantId = token.tenantId,
            customerId = token.customerId,
            tokenHash = token.tokenHash,
            expiresAt = token.expiresAt,
            revokedAt = token.revokedAt,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findByTokenHash(tokenHash: String): PortalRefreshToken? =
        jpa.findByTokenHash(tokenHash)?.toDomain()

    override fun revokeAllForCustomer(customerId: UUID) = jpa.revokeAllForCustomer(customerId, Instant.now())
}

private fun PortalRefreshTokenJpaEntity.toDomain(): PortalRefreshToken =
    PortalRefreshToken.rehydrate(
        id = id,
        tenantId = tenantId,
        customerId = customerId,
        tokenHash = tokenHash,
        expiresAt = expiresAt,
        revokedAt = revokedAt,
    )
