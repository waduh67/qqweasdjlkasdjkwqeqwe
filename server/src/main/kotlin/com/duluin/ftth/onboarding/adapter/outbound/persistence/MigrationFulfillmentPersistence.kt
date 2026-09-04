package com.duluin.ftth.onboarding.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.onboarding.MigrationFulfillmentPublisher
import com.duluin.ftth.onboarding.MigrationFulfillmentRequested
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.PersistenceContext
import jakarta.persistence.EntityManager
import jakarta.persistence.Table
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Entity
@Table(name = "migration_fulfillment_inbox")
class MigrationFulfillmentInboxJpaEntity(
    id: UUID,
    @Column(name = "operation_key", nullable = false, length = 200) var operationKey: String,
    @Column(name = "subscription_id", nullable = false) var subscriptionId: UUID,
    @Column(nullable = false, length = 100) var username: String,
    @Column(name = "plan_id", nullable = false) var planId: UUID,
    @Column(name = "nas_id") var nasId: UUID?,
    @Column(name = "auth_type", nullable = false, length = 20) var authType: String,
    @Column(name = "credential_handle_id") var credentialHandleId: UUID?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) var state: InboxState = InboxState.PENDING,
    @Column(name = "canonical_hash", nullable = false, length = 64) var canonicalHash: String,
) : TenantAwareJpaEntity(id)

enum class InboxState { PENDING, APPROVED, APPLIED, FAILED }

@Component
class MigrationFulfillmentJpaPublisher(
    @PersistenceContext private val entityManager: EntityManager,
) : MigrationFulfillmentPublisher {
    @Transactional
    override fun publish(request: MigrationFulfillmentRequested) {
        val entity = MigrationFulfillmentInboxJpaEntity(
            id = UUID.randomUUID(),
            operationKey = request.operationKey,
            subscriptionId = request.subscriptionId,
            username = request.username,
            planId = request.planId,
            nasId = request.nasId,
            authType = request.authType,
            credentialHandleId = request.credentialHandle?.id,
            canonicalHash = request.canonicalHash.ifBlank { canonicalHash(request) },
        )
        entityManager.persist(entity)
    }

    private fun canonicalHash(request: MigrationFulfillmentRequested): String {
        val canonical = listOf(request.operationKey, request.subscriptionId, request.username, request.planId, request.nasId, request.authType, request.credentialHandle?.id).joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
