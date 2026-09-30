package com.duluin.ftth.fulfillment

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Corrupt only the delivery boundary, after the real repository claims a real approval. */
class FulfillmentEnvelopeFailurePort {
    val fault = AtomicReference<String?>()
    val outcome = AtomicReference<FulfillmentOutcome?>()
    val original = AtomicReference<FulfillmentOutboxRecord?>()
}

@TestConfiguration(proxyBeanMethods = false)
class FulfillmentEnvelopeFailureConfiguration {
    @Bean fun envelopeFailurePort() = FulfillmentEnvelopeFailurePort()
    @Bean @Primary fun alteredEnvelope(delegate: FulfillmentCheckpointPersistenceAdapter, probe: FulfillmentEnvelopeFailurePort): FulfillmentOutboxRepository =
        object : FulfillmentOutboxRepository {
            override fun claimPending(tenantId: UUID, workerId: String, now: Instant, leaseUntil: Instant): FulfillmentOutboxRecord? {
                val actual = delegate.claimPending(tenantId, workerId, now, leaseUntil) ?: return null
                val fault = probe.fault.get() ?: return actual
                probe.original.set(actual)
                return when (fault) {
                    "malformed" -> actual.copy(payload = "not-a-fulfillment-payload")
                    "unsupported-event" -> actual.copy(eventType = "UNSUPPORTED")
                    "foreign-tenant" -> actual.copy(payload = actual.payload.replace(tenantId.toString(), UUID.randomUUID().toString()))
                    else -> error("Unknown envelope fault")
                }
            }
            override fun markOutboxConsumed(id: UUID, workerId: String) = delegate.markOutboxConsumed(id, workerId)
            override fun reconcile(delivery: FulfillmentOutboxRecord, reason: String) = delegate.reconcile(delivery, reason).also { probe.outcome.set(it) }
        }
}
