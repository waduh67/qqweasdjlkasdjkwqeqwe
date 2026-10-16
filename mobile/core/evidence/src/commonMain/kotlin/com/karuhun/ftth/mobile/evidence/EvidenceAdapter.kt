package com.karuhun.ftth.mobile.evidence

import com.karuhun.ftth.mobile.domain.*

class EvidenceOutbox(private val outbox: Outbox) : EvidencePort {
    override suspend fun enqueue(operation: OutboxOperation, bytes: ByteArray) = outbox.enqueue(operation)
}
