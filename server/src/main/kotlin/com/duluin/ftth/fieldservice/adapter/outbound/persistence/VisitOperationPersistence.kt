package com.duluin.ftth.fieldservice.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.fieldservice.application.port.outbound.CommandOutcomeStore
import com.duluin.ftth.fieldservice.domain.model.CommandMetadata
import com.duluin.ftth.fieldservice.application.port.outbound.CommandOutcome
import com.duluin.ftth.common.domain.error.ConflictException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import java.util.UUID

@Entity
@Table(name = "fieldservice_visit_operation")
class VisitOperationJpaEntity(
    id: UUID,
    @Column(name = "visit_id", nullable = false, updatable = false) var visitId: UUID,
    @Column(nullable = false, updatable = false) var namespace: String,
    @Column(name = "operation_key", nullable = false, updatable = false) var operationKey: String,
    @Column(name = "payload_hash", nullable = false, updatable = false) var payloadHash: String,
    @Column(nullable = false, updatable = false) var result: String,
) : TenantAwareJpaEntity(id)

interface VisitOperationJpaRepository : JpaRepository<VisitOperationJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByTenantIdAndNamespaceAndOperationKey(tenantId: UUID, namespace: String, key: String): VisitOperationJpaEntity?

    @Modifying
    @Query(value = "INSERT INTO fieldservice_visit_operation (id, tenant_id, visit_id, namespace, operation_key, payload_hash, result) VALUES (:id, :tenantId, :visitId, :namespace, :operationKey, :payloadHash, :result) ON CONFLICT (tenant_id, namespace, operation_key) DO NOTHING", nativeQuery = true)
    fun insertIfAbsent(id: UUID, tenantId: UUID, visitId: UUID, namespace: String, operationKey: String, payloadHash: String, result: String): Int
}

@Component
class VisitOperationPersistenceAdapter(private val operations: VisitOperationJpaRepository) : CommandOutcomeStore {
    override fun find(command: CommandMetadata): CommandOutcome? = operations.findByTenantIdAndNamespaceAndOperationKey(command.tenantId, command.namespace, command.operationKey)?.let { CommandOutcome(it.namespace, it.operationKey, it.payloadHash, it.result) }

    override fun record(command: CommandMetadata, result: String): CommandOutcome = record(command, command.revisionId(), result)

    override fun record(command: CommandMetadata, targetId: UUID, result: String): CommandOutcome {
        val existing = operations.findByTenantIdAndNamespaceAndOperationKey(command.tenantId, command.namespace, command.operationKey)
        if (existing != null) {
            if (existing.payloadHash != command.payloadHash) throw ConflictException("Operation key was used with a different payload")
            return CommandOutcome(existing.namespace, existing.operationKey, existing.payloadHash, existing.result)
        }
        val inserted = operations.insertIfAbsent(UUID.randomUUID(), command.tenantId, targetId, command.namespace, command.operationKey, command.payloadHash, result)
        val saved = operations.findByTenantIdAndNamespaceAndOperationKey(command.tenantId, command.namespace, command.operationKey)
            ?: throw ConflictException("Visit operation disappeared during concurrent insert")
        if (inserted == 0 && saved.payloadHash != command.payloadHash) throw ConflictException("Operation key was used with a different payload")
        return CommandOutcome(saved.namespace, saved.operationKey, saved.payloadHash, saved.result)
    }

    private fun CommandMetadata.revisionId(): UUID = UUID.nameUUIDFromBytes("$tenantId:$namespace:$operationKey".toByteArray())
}
