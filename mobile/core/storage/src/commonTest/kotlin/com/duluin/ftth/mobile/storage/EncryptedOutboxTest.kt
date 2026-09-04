package com.duluin.ftth.mobile.storage

import com.duluin.ftth.mobile.domain.EnqueueResult
import com.duluin.ftth.mobile.domain.SecureOutboxOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EncryptedOutboxTest {
    @Test
    fun durableReplayAndConflictSurviveRecreation() {
        val records = MemoryRecords()
        val cipher = TestCipher()
        val operation = SecureOutboxOperation("user", "device", "session", "attendance.check-in", "key", "hash-a", 4, byteArrayOf(1, 2))

        assertEquals(EnqueueResult.Accepted, EncryptedOutbox(records, cipher).enqueueSecure(operation))
        assertEquals(EnqueueResult.Replayed, EncryptedOutbox(records, cipher).enqueueSecure(operation))
        assertEquals(EnqueueResult.Conflict, EncryptedOutbox(records, cipher).enqueueSecure(operation.copy(payloadHash = "hash-b")))
    }

    @Test
    fun tamperedCiphertextIsRejectedAndLogoutPurgeRemovesRecords() {
        val records = MemoryRecords()
        val cipher = TestCipher()
        val operation = SecureOutboxOperation("user", "device", "session", "attendance.check-in", "key", "hash", 4, byteArrayOf(1))
        val outbox = EncryptedOutbox(records, cipher)
        outbox.enqueueSecure(operation)
        records.tamper("attendance.check-in:key")

        assertFailsWith<OutboxDecryptionException> { EncryptedOutbox(records, cipher).status() }
        outbox.purge("user")
        assertEquals(0, records.values.size)
    }

    @Test
    fun retryCounterSurvivesRecreation() {
        val records = MemoryRecords()
        val operation = SecureOutboxOperation("user", "device", "session", "attendance.check-in", "key", "hash", 4, byteArrayOf(1))
        EncryptedOutbox(records, TestCipher()).enqueueSecure(operation)

        assertEquals(true, EncryptedOutbox(records, TestCipher()).retry("attendance.check-in:key"))
        assertEquals(1, records.values.getValue("attendance.check-in:key").retries)
    }
}

class MemoryRecords : SecureOutboxRecords {
    val values = mutableMapOf<String, SecureOutboxRecord>()
    override fun entries() = values.values.toList()
    override fun write(record: SecureOutboxRecord) { values["${record.operation.namespace}:${record.operation.key}"] = record }
    override fun delete(userId: String) { values.entries.removeAll { it.value.operation.userId == userId } }
    override fun retry(key: String): Boolean = values[key]?.let { values[key] = it.copy(retries = it.retries + 1); true } ?: false
    fun tamper(key: String) { values[key] = values.getValue(key).copy(payload = EncryptedBlob("test", byteArrayOf(9))) }
}

class TestCipher : OutboxCipher {
    override fun encrypt(payload: ByteArray) = EncryptedBlob("test", payload.map { (it.toInt() xor 0x5A).toByte() }.toByteArray())
    override fun decrypt(blob: EncryptedBlob): ByteArray {
        if (blob.keyVersion != "test" || blob.bytes.size == 1) throw OutboxDecryptionException()
        return blob.bytes.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}
