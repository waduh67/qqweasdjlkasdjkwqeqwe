package com.duluin.ftth.mobile.storage

import com.duluin.ftth.mobile.domain.EnqueueResult
import com.duluin.ftth.mobile.domain.OutboxOperation
import com.duluin.ftth.mobile.domain.OutboxStatus
import com.duluin.ftth.mobile.domain.SecureOutboxOperation
import com.duluin.ftth.mobile.domain.SecureOutboxPort
import com.duluin.ftth.mobile.domain.OutboxIdentity
import com.duluin.ftth.mobile.domain.SecureDeliveryState
import com.duluin.ftth.mobile.domain.SecureOutboxEntry

data class EncryptedBlob(val keyVersion: String, val bytes: ByteArray)
data class SecureOutboxRecord(val operation: SecureOutboxOperation, val payload: EncryptedBlob, val retries: Int, val state: SecureDeliveryState = SecureDeliveryState.QUEUED)

interface SecureOutboxRecords {
    fun entries(): List<SecureOutboxRecord>
    fun write(record: SecureOutboxRecord)
    fun delete(userId: String)
    fun retry(key: String): Boolean
    fun remove(key: String)
}

interface OutboxCipher {
    fun encrypt(payload: ByteArray): EncryptedBlob
    fun decrypt(blob: EncryptedBlob): ByteArray
}

class OutboxDecryptionException : IllegalStateException()
class OutboxUserScopeException : IllegalStateException()

class EncryptedOutbox(
    private val records: SecureOutboxRecords,
    private val cipher: OutboxCipher,
    private val boundUserId: String? = null,
) : SecureOutboxPort {
    override fun enqueue(operation: OutboxOperation): EnqueueResult = EnqueueResult.Conflict

    override fun enqueueSecure(operation: SecureOutboxOperation): EnqueueResult {
        if (boundUserId != null && operation.userId != boundUserId) return EnqueueResult.Conflict
        val identity = identity(operation)
        val existing = scopedEntries().firstOrNull { identity(it.operation) == identity }
        return when {
            existing == null -> {
                records.write(SecureOutboxRecord(operation.copy(payload = byteArrayOf()), cipher.encrypt(operation.payload), retries = 0))
                EnqueueResult.Accepted
            }
            existing.operation.payloadHash == operation.payloadHash && existing.operation.deviceId == operation.deviceId &&
                existing.operation.sessionId == operation.sessionId && existing.operation.revision == operation.revision &&
                cipher.decrypt(existing.payload).contentEquals(operation.payload) -> EnqueueResult.Replayed
            else -> EnqueueResult.Conflict
        }
    }

    override fun retry(key: String): Boolean {
        if (boundUserId != null && key.substringBefore(':') != boundUserId) return false
        return records.retry(key)
    }

    override fun purge(userId: String) {
        if (boundUserId != null && userId != boundUserId) throw OutboxUserScopeException()
        records.delete(userId)
    }
    override fun entries(identity: OutboxIdentity, namespace: String): List<SecureOutboxEntry> = matching(identity, namespace)
        .map { SecureOutboxEntry(it.operation.copy(payload = cipher.decrypt(it.payload)), it.state) }

    override fun mark(identity: OutboxIdentity, namespace: String, key: String, state: SecureDeliveryState) {
        val record = matching(identity, namespace).singleOrNull { it.operation.key == key } ?: throw OutboxUserScopeException()
        records.write(record.copy(state = state))
    }

    override fun complete(identity: OutboxIdentity, namespace: String, key: String) {
        val record = matching(identity, namespace).singleOrNull { it.operation.key == key } ?: throw OutboxUserScopeException()
        records.remove(identity(record.operation))
    }

    private fun matching(identity: OutboxIdentity, namespace: String): List<SecureOutboxRecord> {
        if (boundUserId != null && identity.userId != boundUserId) throw OutboxUserScopeException()
        return scopedEntries().filter { it.operation.let { op -> op.userId == identity.userId && op.deviceId == identity.deviceId && op.sessionId == identity.sessionId && op.namespace == namespace } }
    }
    override fun status(): OutboxStatus {
        val entries = scopedEntries()
        entries.forEach { record ->
            cipher.decrypt(record.payload)
        }
        return OutboxStatus(entries.size, 0, encryptedAtRest = true)
    }

    private fun identity(operation: SecureOutboxOperation) = "${operation.userId}:${operation.namespace}:${operation.key}"
    private fun scopedEntries() = records.entries().filter { boundUserId == null || it.operation.userId == boundUserId }
}
