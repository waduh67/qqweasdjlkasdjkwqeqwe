package com.duluin.ftth.mobile.storage

import com.duluin.ftth.mobile.domain.*

class ContractOutbox : SecureOutboxPort {
    private val delegate = InMemoryOutbox()
    override fun enqueue(operation: OutboxOperation) = delegate.enqueue(operation)
    override fun status() = delegate.status()
}
