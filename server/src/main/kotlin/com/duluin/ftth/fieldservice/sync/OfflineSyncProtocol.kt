package com.duluin.ftth.fieldservice.sync

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SyncState { PENDING, UPLOADING, ACKNOWLEDGED, REJECTED, CONFLICT }

enum class SyncReason {
    ACCEPTED,
    REPLAY,
    PAYLOAD_CONFLICT,
    STALE_REVISION,
    REORDERED_EVENT,
    EXPIRED_EVENT,
    ASSIGNMENT_REVOKED,
    SESSION_EXPIRED,
    INVALID_ATTACHMENT,
    OVERSIZED_ATTACHMENT,
    PARTIAL_UPLOAD,
    RETRY_EXHAUSTED,
    MANUAL_REPAIR_REQUIRED,
}

data class SyncOperation(
    val tenantId: UUID,
    val actorId: UUID,
    val deviceId: String,
    val sessionId: UUID,
    val namespace: String,
    val operationKey: String,
    val payload: String,
    val revision: Long,
    val occurredAt: Instant,
    val payloadHash: String = canonicalHash(tenantId, actorId, deviceId, sessionId, namespace, operationKey, payload, revision),
)

data class SyncReceipt(
    val receiptId: UUID,
    val serverReceivedAt: Instant,
    val state: SyncState,
    val reason: SyncReason,
    val retryable: Boolean,
    val terminal: Boolean,
    val manualRepairRequired: Boolean,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val revision: Long,
)

data class SyncRecord(val operation: SyncOperation, val receipt: SyncReceipt, val attempts: Int)

data class SupervisorOverride(val actorId: UUID, val reason: String, val auditId: UUID) {
    init { require(reason.isNotBlank()) }
}

fun interface SyncPermissionPort {
    fun supervisorOverride(operation: SyncOperation): SupervisorOverride?
}

fun interface SyncMutation {
    fun apply(operation: SyncOperation): SyncReason
}

interface SyncOperationStore {
    fun find(tenantId: UUID, namespace: String, operationKey: String): SyncRecord?
    fun save(record: SyncRecord): SyncRecord
    fun all(): List<SyncRecord>
}

class InMemorySyncOperationStore : SyncOperationStore {
    private val records = ConcurrentHashMap<Triple<UUID, String, String>, SyncRecord>()
    override fun find(tenantId: UUID, namespace: String, operationKey: String) = records[Triple(tenantId, namespace, operationKey)]
    override fun save(record: SyncRecord): SyncRecord {
        records[Triple(record.operation.tenantId, record.operation.namespace, record.operation.operationKey)] = record
        return record
    }
    override fun all(): List<SyncRecord> = records.values.toList()
}

class OfflineSyncService(
    private val store: SyncOperationStore,
    private val clock: () -> Instant = { Instant.now() },
    private val maxEventAge: Duration = Duration.ofHours(72),
    private val maxAttempts: Int = 3,
) {
    init { require(maxAttempts > 0) }

    @Synchronized
    fun apply(operation: SyncOperation, mutation: SyncMutation): SyncReceipt {
        require(operation.payloadHash == canonicalHash(operation.tenantId, operation.actorId, operation.deviceId, operation.sessionId, operation.namespace, operation.operationKey, operation.payload, operation.revision)) {
            "Operation hash does not match canonical payload"
        }
        val existing = store.find(operation.tenantId, operation.namespace, operation.operationKey)
        if (existing != null) {
            if (existing.operation.payloadHash != operation.payloadHash) {
                return receipt(operation, SyncState.CONFLICT, SyncReason.PAYLOAD_CONFLICT, false, true, true)
            }
            return existing.receipt.copy(state = existing.receipt.state)
        }
        val pending = receipt(operation, SyncState.PENDING, SyncReason.ACCEPTED, true, false, false)
        store.save(SyncRecord(operation, pending, 0))
        val age = Duration.between(operation.occurredAt, clock())
        if (age > maxEventAge) return settle(operation, SyncReason.EXPIRED_EVENT, SyncState.REJECTED, false, true, false, 1)
        val uploading = receipt(operation, SyncState.UPLOADING, SyncReason.ACCEPTED, true, false, false)
        store.save(SyncRecord(operation, uploading, 1))
        return try {
            val reason = mutation.apply(operation)
            settle(operation, reason, SyncState.ACKNOWLEDGED, false, true, false, 1)
        } catch (error: RetryableSyncFailure) {
            if (maxAttempts <= 1) settle(operation, SyncReason.MANUAL_REPAIR_REQUIRED, SyncState.REJECTED, false, true, true, 1)
            else settle(operation, error.reason, SyncState.REJECTED, true, false, false, 1)
        } catch (error: ConflictSyncFailure) {
            settle(operation, error.reason, SyncState.CONFLICT, false, true, true, 1)
        } catch (error: RejectedSyncFailure) {
            settle(operation, error.reason, SyncState.REJECTED, error.retryable, !error.retryable, !error.retryable, 1)
        }
    }

    fun retry(operation: SyncOperation, mutation: SyncMutation): SyncReceipt {
        val record = store.find(operation.tenantId, operation.namespace, operation.operationKey) ?: return apply(operation, mutation)
        if (record.operation.payloadHash != operation.payloadHash) return receipt(operation, SyncState.CONFLICT, SyncReason.PAYLOAD_CONFLICT, false, true, true)
        if (record.receipt.state == SyncState.ACKNOWLEDGED || record.receipt.terminal) return record.receipt
        if (record.attempts >= maxAttempts) return settle(operation, SyncReason.MANUAL_REPAIR_REQUIRED, SyncState.REJECTED, false, true, true, record.attempts)
        store.save(record.copy(receipt = record.receipt.copy(state = SyncState.UPLOADING), attempts = record.attempts + 1))
        return try {
            settle(operation, mutation.apply(operation), SyncState.ACKNOWLEDGED, false, true, false, record.attempts + 1)
        } catch (error: RetryableSyncFailure) {
            val exhausted = record.attempts + 1 >= maxAttempts
            settle(operation, if (exhausted) SyncReason.MANUAL_REPAIR_REQUIRED else error.reason, SyncState.REJECTED, !exhausted, exhausted, exhausted, record.attempts + 1)
        } catch (error: ConflictSyncFailure) {
            settle(operation, error.reason, SyncState.CONFLICT, false, true, true, record.attempts + 1)
        } catch (error: RejectedSyncFailure) {
            settle(operation, error.reason, SyncState.REJECTED, error.retryable, !error.retryable, !error.retryable, record.attempts + 1)
        }
    }

    private fun settle(operation: SyncOperation, reason: SyncReason, state: SyncState, retryable: Boolean, terminal: Boolean, repair: Boolean, attempts: Int): SyncReceipt {
        val receipt = receipt(operation, state, reason, retryable, terminal, repair)
        store.save(SyncRecord(operation, receipt, attempts))
        return receipt
    }

    private fun receipt(operation: SyncOperation, state: SyncState, reason: SyncReason, retryable: Boolean, terminal: Boolean, repair: Boolean) = SyncReceipt(UUID.randomUUID(), clock(), state, reason, retryable, terminal, repair, operation.namespace, operation.operationKey, operation.payloadHash, operation.revision)
}

class RetryableSyncFailure(val reason: SyncReason) : RuntimeException()
class ConflictSyncFailure(val reason: SyncReason) : RuntimeException()
class RejectedSyncFailure(val reason: SyncReason, val retryable: Boolean = false) : RuntimeException()

fun canonicalHash(tenantId: UUID, actorId: UUID, deviceId: String, sessionId: UUID, namespace: String, operationKey: String, payload: String, revision: Long): String {
    val canonical = listOf(tenantId, actorId, deviceId, sessionId, namespace, operationKey, revision, payload).joinToString("\u001f")
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
