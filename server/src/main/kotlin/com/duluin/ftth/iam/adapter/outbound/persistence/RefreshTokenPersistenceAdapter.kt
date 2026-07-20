package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.iam.application.port.outbound.RefreshTokenRepository
import com.duluin.ftth.iam.domain.model.RefreshToken
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class RefreshTokenPersistenceAdapter(
    private val jpa: RefreshTokenJpaRepository,
) : RefreshTokenRepository {

    override fun save(token: RefreshToken): RefreshToken {
        val entity = jpa.findById(token.id).orElse(null)?.apply {
            revokedAt = token.revokedAt
        } ?: RefreshTokenJpaEntity(
            id = token.id,
            tenantId = token.tenantId,
            userId = token.userId,
            tokenHash = token.tokenHash,
            expiresAt = token.expiresAt,
            revokedAt = token.revokedAt,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findByTokenHash(tokenHash: String): RefreshToken? =
        jpa.findByTokenHash(tokenHash)?.toDomain()

    override fun revokeAllForUser(userId: UUID) = jpa.revokeAllForUser(userId, Instant.now())
}

private fun RefreshTokenJpaEntity.toDomain(): RefreshToken =
    RefreshToken.rehydrate(
        id = id,
        tenantId = tenantId,
        userId = userId,
        tokenHash = tokenHash,
        expiresAt = expiresAt,
        revokedAt = revokedAt,
    )
