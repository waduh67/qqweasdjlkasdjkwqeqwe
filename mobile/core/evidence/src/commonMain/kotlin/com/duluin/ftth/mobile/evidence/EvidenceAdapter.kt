package com.duluin.ftth.mobile.evidence

import com.duluin.ftth.mobile.domain.*

class EvidenceOutbox(private val outbox: Outbox) : EvidencePort {
    override suspend fun enqueue(operation: OutboxOperation, bytes: ByteArray) = outbox.enqueue(operation)
}
