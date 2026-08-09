package com.duluin.ftth.portal.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import com.duluin.ftth.portal.application.port.outbound.PortalPasswordResetRepository
import com.duluin.ftth.portal.domain.model.PortalPasswordReset
import com.duluin.ftth.portal.domain.model.PortalResetChannel
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Kode pemulihan password portal — SENGAJA bukan tenant-aware, sama alasannya dengan
 * refresh token: kode ditukar oleh orang yang BELUM masuk, jadi tenant context belum
 * terpasang. `tenant_id` disimpan supaya penukaran bisa memasangnya sendiri.
 */
@Entity
@Table(name = "portal_password_reset")
class PortalPasswordResetJpaEntity(
    id: UUID,

    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID,

    @Column(name = "customer_id", nullable = false, updatable = false)
    var customerId: UUID,

    @Column(name = "identifier", nullable = false, updatable = false, length = 255)
    var identifier: String,

    @Column(name = "code_hash", nullable = false, updatable = false, length = 64)
    var codeHash: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 16)
    var channel: PortalResetChannel,

    @Column(name = "expires_at", nullable = false, updatable = false)
    var expiresAt: Instant,

    @Column(name = "attempts", nullable = false)
    var attempts: Int,

    @Column(name = "consumed_at")
    var consumedAt: Instant?,
) : BaseJpaEntity(id)

interface PortalPasswordResetJpaRepository : JpaRepository<PortalPasswordResetJpaEntity, UUID> {

    fun findByCodeHash(codeHash: String): PortalPasswordResetJpaEntity?

    @Modifying
    @Query(
        "update PortalPasswordResetJpaEntity r set r.consumedAt = :now " +
            "where r.customerId = :customerId and r.consumedAt is null",
    )
    fun revokeActiveFor(@Param("customerId") customerId: UUID, @Param("now") now: Instant)

    @Query(
        "select max(r.createdAt) from PortalPasswordResetJpaEntity r where r.customerId = :customerId",
    )
    fun lastIssuedAtFor(@Param("customerId") customerId: UUID): Instant?
}

@Component
class PortalPasswordResetPersistenceAdapter(
    private val jpa: PortalPasswordResetJpaRepository,
) : PortalPasswordResetRepository {

    override fun save(reset: PortalPasswordReset): PortalPasswordReset {
        // Hanya percobaan & penukaran yang bisa berubah — sisanya immutable sejak terbit,
        // jadi cabang "sudah ada" sengaja tak menyentuh kode/identitas/kedaluwarsa.
        val entity = jpa.findById(reset.id).orElse(null)?.apply {
            attempts = reset.attempts
            consumedAt = reset.consumedAt
        } ?: PortalPasswordResetJpaEntity(
            id = reset.id,
            tenantId = reset.tenantId,
            customerId = reset.customerId,
            identifier = reset.identifier,
            codeHash = reset.codeHash,
            channel = reset.channel,
            expiresAt = reset.expiresAt,
            attempts = reset.attempts,
            consumedAt = reset.consumedAt,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findByCodeHash(codeHash: String): PortalPasswordReset? =
        jpa.findByCodeHash(codeHash)?.toDomain()

    override fun revokeActiveFor(customerId: UUID) = jpa.revokeActiveFor(customerId, Instant.now())

    override fun lastIssuedAtFor(customerId: UUID): Instant? = jpa.lastIssuedAtFor(customerId)
}

private fun PortalPasswordResetJpaEntity.toDomain(): PortalPasswordReset =
    PortalPasswordReset.rehydrate(
        id = id,
        tenantId = tenantId,
        customerId = customerId,
        identifier = identifier,
        codeHash = codeHash,
        channel = channel,
        expiresAt = expiresAt,
        attempts = attempts,
        consumedAt = consumedAt,
    )
