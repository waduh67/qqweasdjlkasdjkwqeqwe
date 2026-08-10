package com.duluin.ftth.iam.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RecoveryCodeJpaRepository : JpaRepository<RecoveryCodeJpaEntity, UUID> {

    fun findByUserIdAndCodeHash(userId: UUID, codeHash: String): RecoveryCodeJpaEntity?

    fun countByUserIdAndUsedAtIsNull(userId: UUID): Int

    fun deleteByUserId(userId: UUID)
}
