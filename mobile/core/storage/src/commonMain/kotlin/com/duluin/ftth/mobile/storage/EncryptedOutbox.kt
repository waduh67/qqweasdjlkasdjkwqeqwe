package com.duluin.ftth.mobile.storage

import com.duluin.ftth.mobile.domain.EnqueueResult
import com.duluin.ftth.mobile.domain.OutboxOperation
import com.duluin.ftth.mobile.domain.OutboxStatus
import com.duluin.ftth.mobile.domain.SecureOutboxOperation
import com.duluin.ftth.mobile.domain.SecureOutboxPort

data class EncryptedBlob(val keyVersion: String, val bytes: ByteArray)
data class SecureOutboxRecord(val operation: SecureOutboxOperation, val payload: EncryptedBlob, val retries: Int)

interface SecureOutboxRecords {
    fun entries(): List<SecureOutboxRecord>
    fun write(record: SecureOutboxRecord)
    fun delete(userId: String)
    fun retry(key: String): Boolean
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
            existing.operation.payloadHash == operation.payloadHash -> EnqueueResult.Replayed
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
