package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.PersistenceContext
import jakarta.persistence.EntityManager
import jakarta.persistence.Table
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "fulfillment_checkpoint")
class FulfillmentCheckpointJpaEntity(
    id: UUID,
    @Column(nullable = false, length = 120) var namespace: String,
    @Column(name = "operation_key", nullable = false, length = 240) var operationKey: String,
    @Column(name = "canonical_hash", nullable = false, length = 64) var canonicalHash: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) var source: FulfillmentSource,
    @Column(name = "target_id", nullable = false) var targetId: UUID,
    @Column(name = "subscription_id") var subscriptionId: UUID?,
    @Column(name = "work_order_id") var workOrderId: UUID?,
    @Column(name = "work_order_kind", length = 32) var workOrderKind: String?,
    @Column(name = "required_effects", nullable = false, length = 240) var requiredEffects: String,
    @Column(name = "order_id") var orderId: UUID?,
    @Column(name = "approval_actor_id") var approvalActorId: UUID?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) var state: FulfillmentState,
    @Column(name = "last_effect", length = 32) var lastEffect: FulfillmentEffectType?,
    @Column(nullable = false) var attempts: Int,
    @Column(length = 1000) var outcome: String?,
    @Column(name = "checkpoint_updated_at", nullable = false) var checkpointUpdatedAt: Instant,
) : TenantAwareJpaEntity(id)

@Component
class FulfillmentCheckpointPersistenceAdapter(
    @PersistenceContext private val entityManager: EntityManager,
) : FulfillmentCheckpointRepository, FulfillmentOutboxRepository {
    @Transactional(readOnly = true)
    override fun find(tenantId: UUID, namespace: String, operationKey: String): FulfillmentCheckpoint? = entityManager.createQuery(
        "select c from FulfillmentCheckpointJpaEntity c where c.tenantId = :tenant and c.namespace = :namespace and c.operationKey = :operation",
        FulfillmentCheckpointJpaEntity::class.java,
    ).setParameter("tenant", tenantId).setParameter("namespace", namespace).setParameter("operation", operationKey)
        .resultList.firstOrNull()?.toDomain()

    @Transactional
    override fun claim(tenantId: UUID, namespace: String, operationKey: String): FulfillmentCheckpoint? {
        val id = entityManager.createNativeQuery(
            "SELECT id FROM fulfillment_checkpoint WHERE tenant_id = :tenant AND namespace = :namespace AND operation_key = :operation FOR UPDATE",
        ).setParameter("tenant", tenantId).setParameter("namespace", namespace).setParameter("operation", operationKey)
            .resultList.firstOrNull() ?: return null
        return entityManager.find(FulfillmentCheckpointJpaEntity::class.java, id as UUID)?.toDomain()
    }

    @Transactional
    override fun claimOrCreate(request: FulfillmentRequest): FulfillmentCheckpoint {
        entityManager.createNativeQuery(
            """INSERT INTO fulfillment_checkpoint
               (id, tenant_id, namespace, operation_key, canonical_hash, source, target_id, subscription_id, work_order_id, work_order_kind, required_effects, order_id, approval_actor_id, state, attempts, checkpoint_updated_at)
               VALUES (:id, :tenant, :namespace, :operation, :hash, :source, :target, :subscription, :workOrder, :workOrderKind, :effects, :orderId, :actorId, 'READY', 0, now())
               ON CONFLICT (tenant_id, namespace, operation_key) DO NOTHING""",
        ).setParameter("id", UUID.randomUUID()).setParameter("tenant", request.tenantId)
            .setParameter("namespace", request.namespace).setParameter("operation", request.operationKey)
            .setParameter("hash", request.canonicalHash).setParameter("source", request.source.name)
            .setParameter("target", request.targetId).setParameter("subscription", request.subscriptionId)
            .setParameter("workOrder", request.workOrderId).setParameter("workOrderKind", request.workOrderKind)
            .setParameter("effects", request.requiredEffects.joinToString(",") { it.name })
            .setParameter("orderId", request.orderId).setParameter("actorId", request.approvalActorId).executeUpdate()
        return claim(request.tenantId, request.namespace, request.operationKey)
            ?: error("FULFILLMENT_CLAIM_LOST")
    }

    @Transactional
    override fun save(checkpoint: FulfillmentCheckpoint): FulfillmentCheckpoint {
        val current = find(checkpoint.tenantId, checkpoint.namespace, checkpoint.operationKey)
        val entity = if (current == null) FulfillmentCheckpointJpaEntity(
            UUID.randomUUID(), checkpoint.namespace, checkpoint.operationKey, checkpoint.canonicalHash,
            checkpoint.source, checkpoint.targetId, checkpoint.subscriptionId, checkpoint.workOrderId,
            checkpoint.workOrderKind, checkpoint.requiredEffects.joinToString(",") { it.name }, checkpoint.orderId, checkpoint.approvalActorId, checkpoint.state, checkpoint.lastEffect,
            checkpoint.attempts, checkpoint.outcome, checkpoint.updatedAt,
        ) else entityManager.createQuery(
            "select c from FulfillmentCheckpointJpaEntity c where c.tenantId = :tenant and c.namespace = :namespace and c.operationKey = :operation",
            FulfillmentCheckpointJpaEntity::class.java,
        ).setParameter("tenant", checkpoint.tenantId).setParameter("namespace", checkpoint.namespace).setParameter("operation", checkpoint.operationKey)
            .singleResult.apply {
                state = checkpoint.state
                lastEffect = checkpoint.lastEffect
                attempts = checkpoint.attempts
                outcome = checkpoint.outcome
                checkpointUpdatedAt = checkpoint.updatedAt
            }
        return entityManager.merge(entity).toDomain()
    }

    @Transactional
    override fun enqueueOutbox(checkpoint: FulfillmentCheckpoint) {
        entityManager.createNativeQuery(
            """INSERT INTO fulfillment_outbox (id, tenant_id, fulfillment_id, sequence, event_type, payload_hash, payload)
               SELECT :id, :tenant, id, 1, :eventType, :hash, :payload
               FROM fulfillment_checkpoint
               WHERE tenant_id = :tenant AND namespace = :namespace AND operation_key = :operation
               ON CONFLICT (tenant_id, fulfillment_id, sequence) DO NOTHING""",
        ).setParameter("id", UUID.randomUUID()).setParameter("tenant", checkpoint.tenantId)
            .setParameter("eventType", "FULFILLMENT_APPLY").setParameter("hash", checkpoint.canonicalHash)
            .setParameter("payload", checkpoint.toRequestPayload())
            .setParameter("namespace", checkpoint.namespace).setParameter("operation", checkpoint.operationKey).executeUpdate()
    }

    @Transactional
    override fun markOutboxConsumed(checkpoint: FulfillmentCheckpoint) {
        entityManager.createNativeQuery(
            "UPDATE fulfillment_outbox SET published_at = now() WHERE tenant_id = :tenant AND payload_hash = :hash AND published_at IS NULL",
        ).setParameter("tenant", checkpoint.tenantId).setParameter("hash", checkpoint.canonicalHash).executeUpdate()
    }

    @Transactional
    override fun claimPending(tenantId: UUID, workerId: String, now: Instant, leaseUntil: Instant): FulfillmentOutboxRecord? {
        val row = entityManager.createNativeQuery(
            """WITH candidate AS (
                   SELECT id FROM fulfillment_outbox
                   WHERE tenant_id = :tenant
                     AND published_at IS NULL
                     AND (lease_until IS NULL OR lease_until <= :now)
                   ORDER BY created_at, id
                   FOR UPDATE SKIP LOCKED LIMIT 1
               )
               UPDATE fulfillment_outbox o
               SET claimed_by = :worker, lease_until = :lease, attempts = o.attempts + 1
               FROM candidate c
               WHERE o.id = c.id
               RETURNING o.id, o.tenant_id, o.payload_hash, o.payload""",
        ).setParameter("tenant", tenantId).setParameter("worker", workerId)
            .setParameter("now", now).setParameter("lease", leaseUntil).resultList.firstOrNull() as? Array<*>
            ?: return null
        return FulfillmentOutboxRecord(row[0] as UUID, row[1] as UUID, row[2] as String, row[3] as String, workerId, leaseUntil)
    }

    @Transactional
    override fun markOutboxConsumed(id: UUID, workerId: String) {
        entityManager.createNativeQuery(
            "UPDATE fulfillment_outbox SET published_at = now(), claimed_by = NULL, lease_until = NULL WHERE id = :id AND claimed_by = :worker",
        ).setParameter("id", id).setParameter("worker", workerId).executeUpdate()
    }

    @Transactional(readOnly = true)
    override fun completedEffects(tenantId: UUID, namespace: String, operationKey: String): Set<FulfillmentEffectType> =
        entityManager.createNativeQuery(
            "SELECT effect_type FROM fulfillment_effect_progress p JOIN fulfillment_checkpoint c ON c.id = p.fulfillment_id WHERE p.tenant_id = :tenant AND c.namespace = :namespace AND c.operation_key = :operation AND p.status = 'COMPLETED'",
        ).setParameter("tenant", tenantId).setParameter("namespace", namespace).setParameter("operation", operationKey)
            .resultList.mapTo(linkedSetOf()) { FulfillmentEffectType.valueOf(it as String) }

    @Transactional
    override fun markEffectStarted(tenantId: UUID, namespace: String, operationKey: String, effect: FulfillmentEffectType, at: Instant) {
        entityManager.createNativeQuery(
            """INSERT INTO fulfillment_effect_progress (id, tenant_id, fulfillment_id, effect_type, status, attempts, started_at, updated_at)
               SELECT :id, :tenant, id, :effect, 'STARTED', 1, :at, :at FROM fulfillment_checkpoint
               WHERE tenant_id = :tenant AND namespace = :namespace AND operation_key = :operation
               ON CONFLICT (tenant_id, fulfillment_id, effect_type) DO UPDATE SET status = 'STARTED', attempts = fulfillment_effect_progress.attempts + 1, started_at = :at, updated_at = :at""",
        ).setParameter("id", UUID.randomUUID()).setParameter("tenant", tenantId).setParameter("effect", effect.name)
            .setParameter("namespace", namespace).setParameter("operation", operationKey).setParameter("at", at).executeUpdate()
    }

    @Transactional
    override fun markEffectCompleted(tenantId: UUID, namespace: String, operationKey: String, effect: FulfillmentEffectType, at: Instant) {
        entityManager.createNativeQuery(
            "UPDATE fulfillment_effect_progress p SET status = 'COMPLETED', completed_at = :at, updated_at = :at FROM fulfillment_checkpoint c WHERE p.fulfillment_id = c.id AND p.tenant_id = :tenant AND c.namespace = :namespace AND c.operation_key = :operation AND p.effect_type = :effect",
        ).setParameter("tenant", tenantId).setParameter("namespace", namespace).setParameter("operation", operationKey)
            .setParameter("effect", effect.name).setParameter("at", at).executeUpdate()
    }

    private fun FulfillmentCheckpoint.toRequestPayload() = FulfillmentRequest(
        tenantId = tenantId,
        namespace = namespace,
        operationKey = operationKey,
        canonicalHash = canonicalHash,
        source = source,
        targetId = targetId,
        subscriptionId = subscriptionId,
        workOrderId = workOrderId,
        workOrderKind = workOrderKind,
        approved = true,
        requiredEffects = requiredEffects,
        orderId = orderId,
        approvalActorId = approvalActorId,
    ).encode()

    private fun FulfillmentCheckpointJpaEntity.toDomain() = FulfillmentCheckpoint(
        tenantId ?: error("FULFILLMENT_TENANT_MISSING"), namespace, operationKey, canonicalHash, source,
        targetId, state, lastEffect, attempts, outcome, checkpointUpdatedAt, subscriptionId, workOrderId, workOrderKind,
        requiredEffects.split(',').filter(String::isNotBlank).mapTo(linkedSetOf(), FulfillmentEffectType::valueOf), orderId, approvalActorId,
    )
}
