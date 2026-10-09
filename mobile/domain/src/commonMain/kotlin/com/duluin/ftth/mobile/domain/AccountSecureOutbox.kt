package com.duluin.ftth.mobile.domain

class AccountSecureOutbox(private val create: (String) -> SecureOutboxPort) : SecureOutboxPort {
    private val stores = mutableMapOf<String, SecureOutboxPort>()
    private fun store(userId: String) = stores.getOrPut(userId) { create(userId) }
    override fun enqueueSecure(operation: SecureOutboxOperation) = store(operation.userId).enqueueSecure(operation)
    override fun entries(identity: OutboxIdentity, namespace: String) = store(identity.userId).entries(identity, namespace)
    override fun mark(identity: OutboxIdentity, namespace: String, key: String, state: SecureDeliveryState) = store(identity.userId).mark(identity, namespace, key, state)
    override fun complete(identity: OutboxIdentity, namespace: String, key: String) = store(identity.userId).complete(identity, namespace, key)
    override fun purge(userId: String) { store(userId).purge(userId); stores.remove(userId) }
    override fun enqueue(operation: OutboxOperation): EnqueueResult = error("Account identity is required")
    override fun retry(key: String): Boolean = error("Account identity is required")
    override fun status(): OutboxStatus = error("Account identity is required")
}
