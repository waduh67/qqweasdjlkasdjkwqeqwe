package com.duluin.ftth.mobile.storage

import com.duluin.ftth.mobile.domain.OutboxOperation
import com.duluin.ftth.mobile.domain.SecureOutboxOperation
import com.duluin.ftth.mobile.domain.SecureOutboxPort
import com.duluin.ftth.mobile.domain.OutboxIdentity
import com.duluin.ftth.mobile.domain.SecureDeliveryState

class IosSecureOutbox(userId: String) : SecureOutboxPort {
    private val keyStore = IosKeychainKeyStore(userId)
    private val cipher = IosCryptoKitCipher(keyStore)
    private val records = IosOutboxRecords(userId)
    private val delegate = EncryptedOutbox(records, cipher, userId)

    override fun enqueue(operation: OutboxOperation) = delegate.enqueue(operation)
    override fun enqueueSecure(operation: SecureOutboxOperation) = checkUser(operation.userId).let { delegate.enqueueSecure(operation) }
    override fun retry(key: String): Boolean = delegate.retry(key)
    override fun purge(userId: String) {
        check(userId == keyStore.userId) { "Outbox user does not match the Keychain scope" }
        records.delete(userId)
        keyStore.deleteAllVersions()
    }
    override fun status() = delegate.status()
    override fun entries(identity: OutboxIdentity, namespace: String) = delegate.entries(identity, namespace)
    override fun mark(identity: OutboxIdentity, namespace: String, key: String, state: SecureDeliveryState) = delegate.mark(identity, namespace, key, state)
    override fun complete(identity: OutboxIdentity, namespace: String, key: String) = delegate.complete(identity, namespace, key)

    fun rotateKeyAndMigrate() {
        val oldVersions = keyStore.versions()
        val newVersion = keyStore.rotate()
        val userRecords = records.entries().filter { it.operation.userId == keyStore.userId }
        var deletingOldKeys = false
        try {
            userRecords.filter { it.payload.keyVersion != newVersion }.forEach { record ->
                records.write(record.copy(payload = cipher.encrypt(cipher.decrypt(record.payload))))
            }
            val remaining = records.entries().filter { it.operation.userId == keyStore.userId }
            check(remaining.all { it.payload.keyVersion == newVersion }) { "Rotation left an old key reference" }
            deletingOldKeys = true
            oldVersions.forEach(keyStore::delete)
        } catch (failure: Throwable) {
            if (!deletingOldKeys) userRecords.forEach(records::write)
            throw failure
        }
    }

    private fun checkUser(userId: String) {
        check(userId == keyStore.userId) { "Outbox operation does not match the Keychain scope" }
    }
}
