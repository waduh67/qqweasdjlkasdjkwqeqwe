package com.duluin.ftth.notification.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.outbound.BroadcastDigest
import com.duluin.ftth.notification.application.port.outbound.BroadcastRepository
import com.duluin.ftth.notification.domain.model.Broadcast
import com.duluin.ftth.notification.domain.model.BroadcastRecipient
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class BroadcastPersistenceAdapter(
    private val jpa: BroadcastJpaRepository,
    private val recipientJpa: BroadcastRecipientJpaRepository,
) : BroadcastRepository {

    override fun save(broadcast: Broadcast): Broadcast {
        // Broadcast bersifat append-only; sekali tersimpan tak pernah diperbarui,
        // jadi selalu insert baris baru (broadcast + tiap penerimanya).
        jpa.save(
            BroadcastJpaEntity(
                id = broadcast.id,
                incidentId = broadcast.incidentId,
                channel = broadcast.channel,
                message = broadcast.message,
                createdBy = broadcast.createdBy,
                recipientCount = broadcast.recipientCount,
                sentCount = broadcast.sentCount,
                skippedCount = broadcast.skippedCount,
                failedCount = broadcast.failedCount,
                sentAt = broadcast.createdAt,
            ),
        )
        broadcast.recipients.forEach { r ->
            recipientJpa.save(
                BroadcastRecipientJpaEntity(
                    id = r.id,
                    broadcastId = r.broadcastId,
                    customerId = r.customerId,
                    customerName = r.customerName,
                    phone = r.phone,
                    status = r.status,
                    detail = r.detail,
                    at = r.at,
                ),
            )
        }
        return broadcast
    }

    override fun findById(id: UUID): Broadcast? {
        val entity = jpa.findById(id).orElse(null) ?: return null
        val recipients = recipientJpa.findByBroadcastIdOrderByAt(id).map { it.toDomain() }
        return entity.toDomain(recipients)
    }

    override fun recent(request: PageRequest): Page<BroadcastDigest> =
        // Riwayat selalu terbaru dulu, apa pun sort yang diminta pemanggil.
        jpa.findAll(request.copy(sort = "sentAt", descending = true).toPageable())
            .toDomainPage()
            .map { it.toDigest() }
}

private fun BroadcastJpaEntity.toDigest() = BroadcastDigest(
    id = id,
    incidentId = incidentId,
    channel = channel,
    message = message,
    createdBy = createdBy,
    createdAt = sentAt,
    recipientCount = recipientCount,
    sentCount = sentCount,
    skippedCount = skippedCount,
    failedCount = failedCount,
)

private fun BroadcastJpaEntity.toDomain(recipients: List<BroadcastRecipient>) = Broadcast.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    incidentId = incidentId,
    channel = channel,
    message = message,
    createdBy = createdBy,
    createdAt = sentAt,
    recipients = recipients,
)

private fun BroadcastRecipientJpaEntity.toDomain() = BroadcastRecipient.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    broadcastId = broadcastId,
    customerId = customerId,
    customerName = customerName,
    phone = phone,
    status = status,
    detail = detail,
    at = at,
)
