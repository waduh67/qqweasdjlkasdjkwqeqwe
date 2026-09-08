package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.iam.application.port.outbound.RefreshTokenRepository
import com.duluin.ftth.iam.domain.model.RefreshToken
import com.duluin.ftth.common.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Connection
import java.time.Instant
import java.util.UUID

@Component
class RefreshTokenPersistenceAdapter(
    private val jpa: RefreshTokenJpaRepository,
    private val entityManager: EntityManager,
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

    override fun findTenantByTokenHash(tokenHash: String): UUID? = jpa.findTenantByTokenHash(tokenHash)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun consumeActive(tokenHash: String): RefreshToken? = entityManager.unwrap(Session::class.java).doReturningWork { connection: Connection ->
        check(!connection.autoCommit)
        val found = connection.prepareStatement("SELECT id FROM refresh_token WHERE tenant_id=? AND token_hash=? FOR UPDATE").use {
            it.setObject(1,TenantContext.tenantId()); it.setString(2,tokenHash)
            it.executeQuery().use { rows -> rows.next() }
        }
        if (!found) return@doReturningWork null
        connection.prepareStatement("""UPDATE refresh_token token SET revoked_at=clock_timestamp(),updated_at=clock_timestamp()
            WHERE token.tenant_id=? AND token.token_hash=? AND token.revoked_at IS NULL AND token.expires_at>clock_timestamp()
              AND EXISTS (SELECT 1 FROM app_user actor WHERE actor.id=token.user_id AND actor.tenant_id=token.tenant_id AND actor.status='ACTIVE')
            RETURNING token.id,token.tenant_id,token.user_id,token.token_hash,token.expires_at,token.revoked_at""").use { statement ->
            statement.setObject(1,TenantContext.tenantId()); statement.setString(2,tokenHash)
            statement.executeQuery().use { row ->
                if (!row.next()) null else RefreshToken.rehydrate(row.getObject("id",UUID::class.java),row.getObject("tenant_id",UUID::class.java),
                    row.getObject("user_id",UUID::class.java),row.getString("token_hash"),row.getTimestamp("expires_at").toInstant(),row.getTimestamp("revoked_at").toInstant())
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun revokeByTokenHash(tokenHash: String) {
        entityManager.unwrap(Session::class.java).doWork { connection: Connection ->
            check(!connection.autoCommit)
            connection.prepareStatement("UPDATE refresh_token SET revoked_at=clock_timestamp(),updated_at=clock_timestamp() WHERE tenant_id=? AND token_hash=? AND revoked_at IS NULL").use {
                it.setObject(1,TenantContext.tenantId()); it.setString(2,tokenHash); it.executeUpdate()
            }
        }
    }

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
