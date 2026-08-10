package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.outbound.RecoveryCodeRepository
import com.duluin.ftth.iam.domain.model.RecoveryCode
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class RecoveryCodePersistenceAdapter(
    private val jpa: RecoveryCodeJpaRepository,
) : RecoveryCodeRepository {

    override fun replaceAll(userId: UUID, codes: List<RecoveryCode>) {
        jpa.deleteByUserId(userId)
        // Flush eksplisit lewat saveAll saja tak cukup kalau delete masih tertahan di
        // persistence context: indeks unik (user_id, code_hash) akan menabrak baris lama
        // yang belum benar-benar terhapus di DB.
        jpa.flush()
        jpa.saveAll(codes.map { it.toEntity() })
    }

    override fun findByHash(userId: UUID, codeHash: String): RecoveryCode? =
        jpa.findByUserIdAndCodeHash(userId, codeHash)?.toDomain()

    override fun save(code: RecoveryCode): RecoveryCode {
        val entity = jpa.findById(code.id).orElse(null)?.apply { usedAt = code.usedAt }
            ?: code.toEntity()
        return jpa.save(entity).toDomain()
    }

    override fun countUnused(userId: UUID): Int = jpa.countByUserIdAndUsedAtIsNull(userId)

    override fun deleteAllForUser(userId: UUID) = jpa.deleteByUserId(userId)
}

private fun RecoveryCode.toEntity(): RecoveryCodeJpaEntity =
    RecoveryCodeJpaEntity(id = id, userId = userId, codeHash = codeHash, usedAt = usedAt)

private fun RecoveryCodeJpaEntity.toDomain(): RecoveryCode = RecoveryCode.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    userId = userId,
    codeHash = codeHash,
    usedAt = usedAt,
    createdAt = createdAt,
)
