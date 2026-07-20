package com.duluin.ftth.iam.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface RefreshTokenJpaRepository : JpaRepository<RefreshTokenJpaEntity, UUID> {

    fun findByTokenHash(tokenHash: String): RefreshTokenJpaEntity?

    @Modifying
    @Query(
        "update RefreshTokenJpaEntity t set t.revokedAt = :now " +
            "where t.userId = :userId and t.revokedAt is null",
    )
    fun revokeAllForUser(@Param("userId") userId: UUID, @Param("now") now: Instant)
}
