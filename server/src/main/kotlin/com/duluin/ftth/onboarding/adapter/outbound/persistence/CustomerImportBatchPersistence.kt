package com.duluin.ftth.onboarding.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.onboarding.application.service.CustomerImportBatchState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import jakarta.persistence.LockModeType
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "customer_import_batch")
class CustomerImportBatchJpaEntity(
    id: UUID = UUID.randomUUID(),
) : TenantAwareJpaEntity(id) {
    @Column(name = "operation_key", nullable = false, updatable = false, length = 240)
    lateinit var operationKey: String
    @Column(nullable = false, updatable = false, length = 64)
    lateinit var sha256: String
    @Column(nullable = false, updatable = false, length = 32)
    lateinit var mode: String
    @Column(name = "import_type", nullable = false, updatable = false, length = 40)
    lateinit var importType: String
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    lateinit var state: CustomerImportBatchState
    @Column(name = "object_key", nullable = false, updatable = false, length = 500)
    lateinit var objectKey: String
    @Column(name = "row_count", nullable = false)
    var rowCount: Int = 0
    @Column(name = "schema_version", nullable = false, updatable = false)
    var schemaVersion: Int = 1
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var result: String? = null
    @Column(name = "error_code", length = 40)
    var errorCode: String? = null
    @Column(name = "retention_until")
    var retentionUntil: java.time.Instant? = null
    @Column(name = "report_object_key", length = 500)
    var reportObjectKey: String? = null
    @Column(name = "committed_at")
    var committedAt: java.time.Instant? = null
    @Column(name = "legal_hold", nullable = false)
    var legalHold: Boolean = false
    @Column(name = "commit_operation_key", length = 240)
    var commitOperationKey: String? = null
    @Column(name = "commit_hash", length = 64)
    var commitHash: String? = null
}

@Entity
@Table(name = "customer_import_staging_row")
class CustomerImportStagingRowJpaEntity(
    id: UUID = UUID.randomUUID(),
) : TenantAwareJpaEntity(id) {
    @Column(name = "batch_id", nullable = false, updatable = false)
    lateinit var batchId: UUID
    @Column(name = "row_number", nullable = false, updatable = false)
    var rowNumber: Int = 0
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    lateinit var payload: String
    @Column(name = "credential_handle_id", updatable = false)
    var credentialHandleId: UUID? = null
}

@Entity
@Table(name = "customer_import_error")
class CustomerImportErrorJpaEntity(
    id: UUID = UUID.randomUUID(),
) : TenantAwareJpaEntity(id) {
    @Column(name = "batch_id", nullable = false, updatable = false)
    lateinit var batchId: UUID
    @Column(name = "row_number", nullable = false, updatable = false)
    var rowNumber: Int = 0
    @Column(name = "column_name", length = 100, updatable = false)
    var columnName: String? = null
    @Column(nullable = false, length = 40, updatable = false)
    lateinit var code: String
    @Column(nullable = false, length = 500, updatable = false)
    lateinit var message: String
}

interface CustomerImportBatchJpaRepository : JpaRepository<CustomerImportBatchJpaEntity, UUID> {
    fun findByOperationKey(operationKey: String): CustomerImportBatchJpaEntity?
    fun findBySha256(sha256: String): CustomerImportBatchJpaEntity?
    fun findAllByRetentionUntilBeforeAndLegalHoldFalse(cutoff: java.time.Instant): List<CustomerImportBatchJpaEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from CustomerImportBatchJpaEntity b where b.id = :id")
    fun findForUpdate(id: UUID): CustomerImportBatchJpaEntity?
}

interface CustomerImportStagingRowJpaRepository : JpaRepository<CustomerImportStagingRowJpaEntity, UUID> {
    fun findAllByBatchIdOrderByRowNumber(batchId: UUID): List<CustomerImportStagingRowJpaEntity>
    fun deleteAllByBatchId(batchId: UUID)
}

interface CustomerImportErrorJpaRepository : JpaRepository<CustomerImportErrorJpaEntity, UUID> {
    fun findAllByBatchIdOrderByRowNumber(batchId: UUID): List<CustomerImportErrorJpaEntity>
    fun deleteAllByBatchId(batchId: UUID)
}

@Entity
@Table(name = "customer_import_retention_audit")
class CustomerImportRetentionAuditJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "batch_id", nullable = false, updatable = false)
    lateinit var batchId: UUID
    @Column(nullable = false, length = 40, updatable = false)
    lateinit var outcome: String
    @Column(name = "occurred_at", nullable = false, updatable = false)
    var occurredAt: java.time.Instant = java.time.Instant.now()
}

interface CustomerImportRetentionAuditJpaRepository : JpaRepository<CustomerImportRetentionAuditJpaEntity, UUID>

@Entity
@Table(name = "customer_import_outbox")
class CustomerImportOutboxJpaEntity(
    id: UUID = UUID.randomUUID(),
) : TenantAwareJpaEntity(id) {
    @Column(name = "batch_id", nullable = false, updatable = false)
    lateinit var batchId: UUID
    @Column(name = "operation_key", nullable = false, updatable = false, length = 240)
    lateinit var operationKey: String
    @Column(name = "event_type", nullable = false, updatable = false, length = 80)
    lateinit var eventType: String
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    lateinit var payload: String
    @Column(name = "published_at")
    var publishedAt: java.time.Instant? = null
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0
    @Column(name = "last_error_code", length = 40)
    var lastErrorCode: String? = null
}

interface CustomerImportOutboxJpaRepository : JpaRepository<CustomerImportOutboxJpaEntity, UUID> {
    fun findFirstByPublishedAtIsNullOrderByCreatedAt(): CustomerImportOutboxJpaEntity?
}
