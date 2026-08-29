package com.duluin.ftth.hotspot.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.hotspot.application.port.outbound.VoucherBatchRepository
import com.duluin.ftth.hotspot.application.port.outbound.VoucherClaim
import com.duluin.ftth.hotspot.application.port.outbound.VoucherRepository
import com.duluin.ftth.hotspot.domain.model.Voucher
import com.duluin.ftth.hotspot.domain.model.VoucherBatch
import com.duluin.ftth.hotspot.domain.model.VoucherStatus
import org.springframework.data.domain.PageRequest as SpringPageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class VoucherPersistenceAdapter(
    private val jpa: VoucherJpaRepository,
    private val cipher: SecretCipher,
) : VoucherRepository {
    override fun save(voucher: Voucher, password: String?): Voucher {
        val encryptedPassword = password?.takeIf { it.isNotBlank() }?.let(cipher::encrypt)
        val entity = jpa.findById(voucher.id).orElse(null)?.apply {
            status = voucher.status
            activatedAt = voucher.activatedAt
            expiresAt = voucher.expiresAt
            deviceId = voucher.deviceId
            revokedAt = voucher.revokedAt
            revokedBy = voucher.revokedBy
            revocationReason = voucher.revocationReason
            if (encryptedPassword != null) passwordCiphertext = encryptedPassword
        } ?: VoucherJpaEntity(
            voucher.id, voucher.batchId, voucher.username,
            encryptedPassword ?: error("Password voucher wajib diisi"), voucher.siteId, voucher.planId,
            voucher.duration.seconds, voucher.status, voucher.activatedAt, voucher.expiresAt, voucher.deviceId,
            voucher.revokedAt, voucher.revokedBy, voucher.revocationReason,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Voucher? = jpa.findById(id).orElse(null)?.toDomain()

    override fun claim(id: UUID, deviceId: String, clock: Clock): VoucherClaim? {
        val entity = jpa.findLockedById(id) ?: return null
        val voucher = entity.toDomain()
        val activated = voucher.status == VoucherStatus.AVAILABLE
        voucher.claim(deviceId, clock)
        entity.apply {
            status = voucher.status
            activatedAt = voucher.activatedAt
            expiresAt = voucher.expiresAt
            this.deviceId = voucher.deviceId
        }
        jpa.save(entity)
        return VoucherClaim(voucher, activated)
    }

    override fun expireIfDue(id: UUID, now: Instant): Voucher? {
        val entity = jpa.findLockedById(id) ?: return null
        val voucher = entity.toDomain()
        if (!voucher.expireIfDue(now)) return null
        entity.status = voucher.status
        jpa.save(entity)
        return voucher
    }

    override fun findActiveExpired(now: Instant, limit: Int): List<Voucher> =
        jpa.findIdsByStatusAndExpiresAtBeforeOrEqual(VoucherStatus.ACTIVE, now, SpringPageRequest.of(0, limit))
            .mapNotNull(::findById)

    override fun search(batchId: UUID?, siteId: UUID?, status: VoucherStatus?, page: PageRequest): Page<Voucher> {
        val result = jpa.search(batchId, siteId, status, page.toSpringPage())
        return Page(result.content.map { it.toDomain() }, result.number, result.size, result.totalElements)
    }

    private fun VoucherJpaEntity.toDomain() = Voucher.rehydrate(
        id, tenantId ?: TenantContext.tenantId(), batchId, username, siteId, planId, Duration.ofSeconds(durationSeconds),
        status, activatedAt, expiresAt, deviceId, revokedAt, revokedBy, revocationReason,
    )
}

@Component
class VoucherBatchPersistenceAdapter(private val jpa: VoucherBatchJpaRepository) : VoucherBatchRepository {
    override fun save(batch: VoucherBatch): VoucherBatch {
        val entity = jpa.findById(batch.id).orElse(null)?.apply { status = batch.status } ?: VoucherBatchJpaEntity(
            batch.id, batch.siteId, batch.planId, batch.duration.seconds, batch.status,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): VoucherBatch? = jpa.findById(id).orElse(null)?.toDomain()

    override fun search(siteId: UUID?, page: PageRequest): Page<VoucherBatch> {
        val result = jpa.search(siteId, page.toSpringPage())
        return Page(result.content.map { it.toDomain() }, result.number, result.size, result.totalElements)
    }

    private fun VoucherBatchJpaEntity.toDomain() = VoucherBatch.rehydrate(
        id, tenantId ?: TenantContext.tenantId(), siteId, planId, Duration.ofSeconds(durationSeconds), status,
    )
}

private fun PageRequest.toSpringPage(): SpringPageRequest {
    val direction = if (descending) Sort.Direction.DESC else Sort.Direction.ASC
    return SpringPageRequest.of(page, size, Sort.by(direction, sort ?: "id"))
}
